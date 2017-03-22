package org.apache.maven.surefire.booter;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.surefire.providerapi.ProviderParameters;
import org.apache.maven.surefire.providerapi.SurefireProvider;
import org.apache.maven.surefire.report.LegacyPojoStackTraceWriter;
import org.apache.maven.surefire.report.ReporterFactory;
import org.apache.maven.surefire.report.StackTraceWriter;
import org.apache.maven.surefire.suite.RunResult;
import org.apache.maven.surefire.testset.TestSetFailedException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.Math.max;
import static java.lang.System.err;
import static java.lang.System.out;
import static java.lang.System.setErr;
import static java.lang.System.setOut;
import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.maven.surefire.booter.CommandReader.getReader;
import static org.apache.maven.surefire.booter.ForkingRunListener.BOOTERCODE_BYE;
import static org.apache.maven.surefire.booter.ForkingRunListener.BOOTERCODE_ERROR;
import static org.apache.maven.surefire.booter.ForkingRunListener.encode;
import static org.apache.maven.surefire.booter.SystemPropertyManager.setSystemProperties;
import static org.apache.maven.surefire.util.ReflectionUtils.instantiateOneArg;
import static org.apache.maven.surefire.util.internal.DaemonThreadFactory.newDaemonThreadFactory;
import static org.apache.maven.surefire.util.internal.StringUtils.encodeStringForForkCommunication;

/**
 * The part of the booter that is unique to a forked vm.
 * <p/>
 * Deals with deserialization of the booter wire-level protocol
 * <p/>
 *
 * @author Jason van Zyl
 * @author Emmanuel Venisse
 * @author Kristian Rosenvold
 */
public final class ForkedBooter
{
    private static final long DEFAULT_SYSTEM_EXIT_TIMEOUT_IN_SECONDS = 30;
    private static final long PING_TIMEOUT_IN_SECONDS = 20;
    private static final long ONE_SECOND_IN_MILLIS = 1000;
    private static final ScheduledExecutorService JVM_PING = createPingScheduler();

    private static volatile ScheduledThreadPoolExecutor jvmTerminator;
    private static volatile long systemExitTimeoutInSeconds = DEFAULT_SYSTEM_EXIT_TIMEOUT_IN_SECONDS;
    /*private static final Logger LOG = Logger.getLogger(ForkedBooter.class.getName());

    static {
        try {
            File log = new File(getProperty("user.dir"), nanoTime() + ".log");
            FileHandler fh = new FileHandler(log.getCanonicalPath());
            LOG.addHandler(fh);
            fh.setFormatter(new SimpleFormatter());
        } catch (IOException e) {
            throw new IllegalStateException(e.getLocalizedMessage(), e);
        }
    }*/

    /**
     * This method is invoked when Surefire is forked - this method parses and organizes the arguments passed to it and
     * then calls the Surefire class' run method. <p/> The system exit code will be 1 if an exception is thrown.
     *
     * @param args Commandline arguments
     */
    public static void main( String... args ) throws Exception
    {
        //LOG.info( "ForkedBooter.main() :: Forked JVM started." );
        final CommandReader reader = startupMasterProcessReader();
        final ScheduledFuture<?> pingScheduler = listenToShutdownCommands( reader );
        final PrintStream originalOut = out;
        try
        {
            final String tmpDir = args[0];
            final String dumpFileName = args[1];
            final String surefirePropsFileName = args[2];

            BooterDeserializer booterDeserializer =
                    new BooterDeserializer( createSurefirePropertiesIfFileExists( tmpDir, surefirePropsFileName ) );
            if ( args.length > 3 )
            {
                final String effectiveSystemPropertiesFileName = args[3];
                setSystemProperties( new File( tmpDir, effectiveSystemPropertiesFileName ) );
            }

            final ProviderConfiguration providerConfiguration = booterDeserializer.deserialize();
            DumpErrorSingleton.getSingleton().init( dumpFileName, providerConfiguration.getReporterConfiguration() );

            final StartupConfiguration startupConfiguration = booterDeserializer.getProviderConfiguration();
            systemExitTimeoutInSeconds =
                    providerConfiguration.systemExitTimeout( DEFAULT_SYSTEM_EXIT_TIMEOUT_IN_SECONDS );
            final TypeEncodedValue forkedTestSet = providerConfiguration.getTestForFork();
            final boolean readTestsFromInputStream = providerConfiguration.isReadTestsFromInStream();

            final ClasspathConfiguration classpathConfiguration = startupConfiguration.getClasspathConfiguration();
            if ( startupConfiguration.isManifestOnlyJarRequestedAndUsable() )
            {
                classpathConfiguration.trickClassPathWhenManifestOnlyClasspath();
            }

            final ClassLoader classLoader = currentThread().getContextClassLoader();
            classLoader.setDefaultAssertionStatus( classpathConfiguration.isEnableAssertions() );
            startupConfiguration.writeSurefireTestClasspathProperty();

            final Object testSet;
            if ( forkedTestSet != null )
            {
                testSet = forkedTestSet.getDecodedValue( classLoader );
            }
            else if ( readTestsFromInputStream )
            {
                testSet = new LazyTestsToRun( originalOut );
            }
            else
            {
                testSet = null;
            }

            try
            {
                runSuitesInProcess( testSet, startupConfiguration, providerConfiguration, originalOut );
            }
            catch ( InvocationTargetException t )
            {
                DumpErrorSingleton.getSingleton().dumpException( t );
                StackTraceWriter stackTraceWriter =
                    new LegacyPojoStackTraceWriter( "test subsystem", "no method", t.getTargetException() );
                StringBuilder stringBuilder = new StringBuilder();
                encode( stringBuilder, stackTraceWriter, false );
                encodeAndWriteToOutput( ( (char) BOOTERCODE_ERROR ) + ",0," + stringBuilder + "\n" , originalOut );
            }
            catch ( Throwable t )
            {
                DumpErrorSingleton.getSingleton().dumpException( t );
                StackTraceWriter stackTraceWriter = new LegacyPojoStackTraceWriter( "test subsystem", "no method", t );
                StringBuilder stringBuilder = new StringBuilder();
                encode( stringBuilder, stackTraceWriter, false );
                encodeAndWriteToOutput( ( (char) BOOTERCODE_ERROR ) + ",0," + stringBuilder + "\n", originalOut );
            }
            // Say bye.
            encodeAndWriteToOutput( ( (char) BOOTERCODE_BYE ) + ",0,BYE!\n", originalOut );
            // noinspection CallToSystemExit
            exit( 0, reader );
        }
        catch ( Throwable t )
        {
            DumpErrorSingleton.getSingleton().dumpException( t );
            // Just throwing does getMessage() and a local trace - we want to call printStackTrace for a full trace
            // noinspection UseOfSystemOutOrSystemErr
            t.printStackTrace( err );
            // noinspection ProhibitedExceptionThrown,CallToSystemExit
            exit( 1 );
        }
        finally
        {
            pingScheduler.cancel( true );
        }
        //LOG.info( "ForkedBooter.main() :: Forked JVM finished." );
    }

    private static CommandReader startupMasterProcessReader()
    {
        return getReader();
    }

    private static ScheduledFuture<?> listenToShutdownCommands( CommandReader reader )
    {
        reader.addShutdownListener( createExitHandler() );
        AtomicBoolean pingDone = new AtomicBoolean( true );
        reader.addNoopListener( createPingHandler( pingDone ) );
        Runnable pingJob = createPingJob( pingDone );
        return JVM_PING.scheduleAtFixedRate( pingJob, 0, PING_TIMEOUT_IN_SECONDS, SECONDS );
    }

    private static CommandListener createPingHandler( final AtomicBoolean pingDone )
    {
        return new CommandListener()
        {
            public void update( Command command )
            {
                pingDone.set( true );
            }
        };
    }

    private static CommandListener createExitHandler()
    {
        return new CommandListener()
        {
            public void update( Command command )
            {
                System.out.println( System.currentTimeMillis() + " ForkedBooter exit handler" );
                Shutdown shutdown = command.toShutdownData();
                if ( shutdown.isKill() )
                {
                    kill();
                }
                else if ( shutdown.isExit() )
                {
                    exit( 1 );
                }
                // else refers to shutdown=testset, but not used now, keeping reader open
            }
        };
    }

    private static Runnable createPingJob( final AtomicBoolean pingDone  )
    {
        return new Runnable()
        {
            public void run()
            {
                System.out.println( System.currentTimeMillis() + " ForkedBooter NOOP timer" );
                boolean hasPing = pingDone.getAndSet( false );
                if ( !hasPing )
                {
                    System.out.println( System.currentTimeMillis()
                                        + " ForkedBooter PING timer: plugin did not send me NOOP signal > exit" );
                    kill();
                }
            }
        };
    }

    private static void encodeAndWriteToOutput( String string, PrintStream out )
    {
        byte[] encodeBytes = encodeStringForForkCommunication( string );
        synchronized ( out )
        {
            out.write( encodeBytes, 0, encodeBytes.length );
            out.flush();
        }
    }

    private static void kill()
    {
        Runtime.getRuntime().halt( 1 );
    }

    private static void exit( int returnCode )
    {
        launchLastDitchDaemonShutdownThread( returnCode );
        System.exit( returnCode );
    }

    private static void exit( int returnCode, final CommandReader reader )
    {
        final Semaphore barrier = new Semaphore( 0 );
        reader.addByeAckListener( new CommandListener()
                                  {
                                      @Override
                                      public void update( Command command )
                                      {
                                          barrier.release();
                                      }
                                  }
        );
        launchLastDitchDaemonShutdownThread( returnCode );
        final long timeoutMillis = max( systemExitTimeoutInSeconds * ONE_SECOND_IN_MILLIS, ONE_SECOND_IN_MILLIS );
        acquireOnePermit( barrier, timeoutMillis );
        System.exit( returnCode );
    }

    private static boolean acquireOnePermit( Semaphore barrier, long timeoutMillis )
    {
        try
        {
            return barrier.tryAcquire( timeoutMillis, MILLISECONDS );
        }
        catch ( InterruptedException e )
        {
            return true;
        }
    }

    private static RunResult runSuitesInProcess( Object testSet, StartupConfiguration startupConfiguration,
                                                 ProviderConfiguration providerConfiguration,
                                                 PrintStream originalSystemOut )
        throws SurefireExecutionException, TestSetFailedException, InvocationTargetException
    {
        final ReporterFactory factory = createForkingReporterFactory( providerConfiguration, originalSystemOut );

        return invokeProviderInSameClassLoader( testSet, factory, providerConfiguration, true, startupConfiguration,
                                                      false );
    }

    private static ReporterFactory createForkingReporterFactory( ProviderConfiguration providerConfiguration,
                                                                 PrintStream originalSystemOut )
    {
        final boolean trimStackTrace = providerConfiguration.getReporterConfiguration().isTrimStackTrace();
        return new ForkingReporterFactory( trimStackTrace, originalSystemOut );
    }

    private static synchronized ScheduledThreadPoolExecutor getJvmTerminator()
    {
        if ( jvmTerminator == null )
        {
            ThreadFactory threadFactory =
                    newDaemonThreadFactory( "last-ditch-daemon-shutdown-thread-" + systemExitTimeoutInSeconds + "s" );
            jvmTerminator = new ScheduledThreadPoolExecutor( 1, threadFactory );
            jvmTerminator.setMaximumPoolSize( 1 );
            return jvmTerminator;
        }
        else
        {
            return jvmTerminator;
        }
    }

    private static ScheduledExecutorService createPingScheduler()
    {
        ThreadFactory threadFactory = newDaemonThreadFactory( "ping-" + PING_TIMEOUT_IN_SECONDS + "s" );
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor( 1, threadFactory );
        executor.setMaximumPoolSize( 2 );
        executor.prestartCoreThread();
        return executor;
    }

    @SuppressWarnings( "checkstyle:emptyblock" )
    private static void launchLastDitchDaemonShutdownThread( final int returnCode )
    {
        getJvmTerminator().schedule( new Runnable()
                                        {
                                            public void run()
                                            {
                                                Runtime.getRuntime().halt( returnCode );
                                            }
                                        }, systemExitTimeoutInSeconds, SECONDS
        );
    }

    private static RunResult invokeProviderInSameClassLoader( Object testSet, Object factory,
                                                              ProviderConfiguration providerConfig,
                                                              boolean insideFork,
                                                              StartupConfiguration startupConfig,
                                                              boolean restoreStreams )
        throws TestSetFailedException, InvocationTargetException
    {
        final PrintStream orgSystemOut = out;
        final PrintStream orgSystemErr = err;
        // Note that System.out/System.err are also read in the "ReporterConfiguration" instatiation
        // in createProvider below. These are the same values as here.

        try
        {
            return createProviderInCurrentClassloader( startupConfig, insideFork, providerConfig, factory )
                           .invoke( testSet );
        }
        finally
        {
            if ( restoreStreams && System.getSecurityManager() == null )
            {
                setOut( orgSystemOut );
                setErr( orgSystemErr );
            }
        }
    }

    private static SurefireProvider createProviderInCurrentClassloader( StartupConfiguration startupConfiguration,
                                                                        boolean isInsideFork,
                                                                       ProviderConfiguration providerConfiguration,
                                                                       Object reporterManagerFactory )
    {
        BaseProviderFactory bpf = new BaseProviderFactory( (ReporterFactory) reporterManagerFactory, isInsideFork );
        bpf.setTestRequest( providerConfiguration.getTestSuiteDefinition() );
        bpf.setReporterConfiguration( providerConfiguration.getReporterConfiguration() );
        ClassLoader classLoader = currentThread().getContextClassLoader();
        bpf.setClassLoaders( classLoader );
        bpf.setTestArtifactInfo( providerConfiguration.getTestArtifact() );
        bpf.setProviderProperties( providerConfiguration.getProviderProperties() );
        bpf.setRunOrderParameters( providerConfiguration.getRunOrderParameters() );
        bpf.setDirectoryScannerParameters( providerConfiguration.getDirScannerParams() );
        bpf.setMainCliOptions( providerConfiguration.getMainCliOptions() );
        bpf.setSkipAfterFailureCount( providerConfiguration.getSkipAfterFailureCount() );
        bpf.setShutdown( providerConfiguration.getShutdown() );
        bpf.setSystemExitTimeout( providerConfiguration.getSystemExitTimeout() );
        String providerClass = startupConfiguration.getActualClassName();
        return (SurefireProvider) instantiateOneArg( classLoader, providerClass, ProviderParameters.class, bpf );
    }

    private static InputStream createSurefirePropertiesIfFileExists( String tmpDir, String propFileName )
            throws FileNotFoundException
    {
        File surefirePropertiesFile = new File( tmpDir, propFileName );
        return surefirePropertiesFile.exists() ? new FileInputStream( surefirePropertiesFile ) : null;
    }
}
