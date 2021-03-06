/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2018 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.onejar;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.util.ServerInfo;

import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class TomcatOnejarRunner
{
    final OnejarMain onejarMain;

    public TomcatOnejarRunner( final OnejarMain onejarMain )
    {
        this.onejarMain = onejarMain;
    }

    private void explodeWar( final OnejarConfig onejarConfig ) throws IOException
    {
        final InputStream warSource = onejarConfig.getWar();
        final ZipInputStream zipInputStream = new ZipInputStream( warSource );
        final File outputFolder = onejarConfig.getWarFolder( );

        ArgumentParser.mkdirs( outputFolder );

        ZipEntry zipEntry = zipInputStream.getNextEntry();

        while ( zipEntry != null )
        {
            final String fileName = zipEntry.getName();
            final File newFile = new File( outputFolder + File.separator + fileName );

            if ( !zipEntry.isDirectory() )
            {
                ArgumentParser.mkdirs( newFile.getParentFile() );
                Files.copy( zipInputStream, newFile.toPath() );
            }
            zipEntry = zipInputStream.getNextEntry();
        }

    }

    void startTomcat( final OnejarConfig onejarConfig )
            throws ServletException, IOException, OnejarException
    {
        final Instant startTime = Instant.now();

        purgeDirectory( onejarConfig.getWorkingPath().toPath() );

        explodeWar( onejarConfig );
        out( "deployed war" );

        try
        {
            generatePwmKeystore( onejarConfig );
            out( "keystore generated" );
        }
        catch ( Exception e )
        {
            throw new OnejarException( "error generating keystore: " + e.getMessage() );
        }

        outputPwmAppProperties( onejarConfig );

        setupEnv( onejarConfig );

        final Tomcat tomcat = new Tomcat();

        {
            final File basePath = new File( onejarConfig.getWorkingPath().getPath() + File.separator + "b" );
            ArgumentParser.mkdirs( basePath );
            tomcat.setBaseDir( basePath.getAbsolutePath() );
        }
        {
            final File basePath = new File( onejarConfig.getWorkingPath().getPath() + File.separator + "a" );
            ArgumentParser.mkdirs( basePath );
            tomcat.getServer().setCatalinaBase( basePath );
            tomcat.getServer().setCatalinaHome( basePath );
        }
        {
            final File workPath = new File( onejarConfig.getWorkingPath().getPath() + File.separator + "w" );
            ArgumentParser.mkdirs( workPath );
            tomcat.getHost().setAppBase( workPath.getAbsolutePath() );
        }

        tomcat.getHost().setAutoDeploy( false );
        tomcat.getHost().setDeployOnStartup( false );

        deployRedirectConnector( tomcat, onejarConfig );

        final String warPath = onejarConfig.getWarFolder().getAbsolutePath();
        tomcat.addWebapp( "/" + onejarConfig.getContext(), warPath );


        try
        {
            tomcat.start();

            tomcat.setConnector( makeConnector( onejarConfig ) );

            out( "tomcat started in " + Duration.between( Instant.now(), startTime ).toString() );
        }
        catch ( LifecycleException e )
        {
            throw new OnejarException( "unable to start tomcat: " + e.getMessage() );
        }
        tomcat.getServer().await();

        System.out.println( "\nexiting..." );
    }

    private void deployRedirectConnector( final Tomcat tomcat, final OnejarConfig onejarConfig )
            throws IOException, ServletException
    {
        final String srcRootWebXml = "ROOT-redirect-webapp/WEB-INF/web.xml";
        final String srcRootIndex = "ROOT-redirect-webapp/WEB-INF/index.jsp";

        final File redirBase = new File( onejarConfig.getWorkingPath().getAbsoluteFile() + File.separator + "redirectBase" );
        ArgumentParser.mkdirs( redirBase );
        {
            ArgumentParser.mkdirs( new File ( redirBase.getAbsolutePath() + File.separator + "WEB-INF" ) );
            copyFileAndReplace(
                    srcRootWebXml,
                    redirBase.getPath() + File.separator + "WEB-INF" + File.separator + "web.xml",
                    onejarConfig.getContext() );
            copyFileAndReplace(
                    srcRootIndex,
                    redirBase.getPath() + File.separator +  "WEB-INF" + File.separator + "index.jsp",
                    onejarConfig.getContext() );
        }

        tomcat.addWebapp( "", redirBase.getAbsolutePath() );
    }


    private Connector makeConnector( final OnejarConfig onejarConfig )
    {
        final Connector connector = new Connector( "HTTP/1.1" );
        connector.setPort( onejarConfig.getPort() );

        if ( onejarConfig.getLocalAddress() != null && !onejarConfig.getLocalAddress().isEmpty() )
        {
            connector.setProperty( "address", onejarConfig.getLocalAddress() );
        }
        connector.setSecure( true );
        connector.setScheme( "https" );
        connector.setAttribute( "SSLEnabled", "true" );
        connector.setAttribute( "keystoreFile", onejarConfig.getKeystoreFile().getAbsolutePath() );
        connector.setAttribute( "keystorePass", onejarConfig.getKeystorePass() );
        connector.setAttribute( "keyAlias", OnejarMain.KEYSTORE_ALIAS );
        connector.setAttribute( "clientAuth", "false" );

        return connector;
    }

     static String getVersion( ) throws OnejarException
    {
        try
        {
            final Class clazz = TomcatOnejarRunner.class;
            final String className = clazz.getSimpleName() + ".class";
            final String classPath = clazz.getResource( className ).toString();
            if ( !classPath.startsWith( "jar" ) )
            {
                // Class not from JAR
                return "version missing, not running inside jar";
            }
            final String manifestPath = classPath.substring( 0, classPath.lastIndexOf( "!" ) + 1 )
                    + "/META-INF/MANIFEST.MF";
            final Manifest manifest = new Manifest( new URL( manifestPath ).openStream() );
            final Attributes attr = manifest.getMainAttributes();
            return attr.getValue( "Implementation-Version-Display" )
                    + "  [" + ServerInfo.getServerInfo() + "]";
        }
        catch ( IOException e )
        {
            throw new OnejarException( "error reading internal version info: " + e.getMessage() );
        }
    }

    private void purgeDirectory( final Path rootPath )
            throws IOException
    {
        System.out.println( "purging work directory: " + rootPath );
        Files.walk( rootPath, FileVisitOption.FOLLOW_LINKS )
                .sorted( Comparator.reverseOrder() )
                .map( Path::toFile )
                .filter( file -> !rootPath.toString().equals( file.getPath() ) )
                .forEach( File::delete );
    }


    void out( final String output )
    {
        onejarMain.out( output );
    }


    void generatePwmKeystore( final OnejarConfig onejarConfig )
            throws IOException, ClassNotFoundException, IllegalAccessException, NoSuchMethodException, InvocationTargetException
    {
        final File warPath = onejarConfig.getWarFolder();
        final String keystoreFile = onejarConfig.getKeystoreFile().getAbsolutePath();
        final File webInfPath = new File( warPath.getAbsolutePath() + File.separator + "WEB-INF" + File.separator + "lib" );
        final File[] jarFiles = webInfPath.listFiles();
        final List<URL> jarURLList = new ArrayList<>();
        if ( jarFiles != null )
        {
            for ( final File jarFile : jarFiles )
            {
                jarURLList.add( jarFile.toURI().toURL() );
            }
        }
        final URLClassLoader classLoader = URLClassLoader.newInstance( jarURLList.toArray( new URL[ jarURLList.size() ] ) );
        final Class pwmMainClass = classLoader.loadClass( "password.pwm.util.cli.MainClass" );
        final Method mainMethod = pwmMainClass.getMethod( "main", String[].class );
        final String[] arguments = new String[] {
                "-applicationPath=" + onejarConfig.getApplicationPath().getAbsolutePath(),
                "ExportHttpsKeyStore",
                keystoreFile,
                OnejarMain.KEYSTORE_ALIAS,
                onejarConfig.getKeystorePass(),
        };

        mainMethod.invoke( null, ( Object ) arguments );
        classLoader.close();
    }

    void setupEnv( final OnejarConfig onejarConfig )
    {
        final String envVarPrefix = Resource.envVarPrefix.getValue();
        System.setProperty( envVarPrefix + "_APPLICATIONPATH", onejarConfig.getApplicationPath().getAbsolutePath() );
        System.setProperty( envVarPrefix + "_APPLICATIONFLAGS", "ManageHttps" );
        System.setProperty( envVarPrefix + "_APPLICATIONPARAMFILE", onejarConfig.getPwmAppPropertiesFile().getAbsolutePath() );
    }

    void outputPwmAppProperties( final OnejarConfig onejarConfig ) throws IOException
    {
        final Properties properties = new Properties();
        properties.setProperty( "AutoExportHttpsKeyStoreFile", onejarConfig.getKeystoreFile().getAbsolutePath() );
        properties.setProperty( "AutoExportHttpsKeyStorePassword", onejarConfig.getKeystorePass() );
        properties.setProperty( "AutoExportHttpsKeyStoreAlias", OnejarMain.KEYSTORE_ALIAS );
        final File propFile = onejarConfig.getPwmAppPropertiesFile( );
        try ( Writer writer = new OutputStreamWriter( new FileOutputStream( propFile ), StandardCharsets.UTF_8 ) )
        {
            properties.store( writer, "auto-generated file" );
        }
    }

    void copyFileAndReplace(
            final String srcPath,
            final String destPath,
            final String rootcontext
    )
            throws IOException
    {
        try ( InputStream inputStream = TomcatOnejarRunner.class.getClassLoader().getResourceAsStream( srcPath ) )
        {
            try ( BufferedReader reader = new BufferedReader( new InputStreamReader( inputStream, "UTF8" ) ) )
            {
                String contents = reader.lines().collect( Collectors.joining( "\n" ) );
                contents = contents.replace( "[[[ROOT_CONTEXT]]]", rootcontext );
                Files.write( Paths.get( destPath ), contents.getBytes( "UTF8" ) );
            }
        }
    }
}
