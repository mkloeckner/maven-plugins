package com.goldin.plugins.common

import static org.twdata.maven.mojoexecutor.MojoExecutor.*
import com.goldin.gcommons.GCommons
import com.goldin.gcommons.util.GroovyConfig
import groovy.text.SimpleTemplateEngine
import groovy.text.Template
import org.apache.maven.Maven
import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.factory.ArtifactFactory
import org.apache.maven.artifact.metadata.ArtifactMetadataSource
import org.apache.maven.artifact.resolver.ArtifactResolver
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter
import org.apache.maven.execution.MavenSession
import org.apache.maven.monitor.logging.DefaultLog
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.PluginManager
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import org.apache.maven.shared.filtering.MavenFileFilter
import org.apache.maven.shared.filtering.MavenResourcesExecution
import org.codehaus.plexus.logging.Logger
import org.codehaus.plexus.logging.console.ConsoleLogger
import org.twdata.maven.mojoexecutor.MojoExecutor.Element
import org.xml.sax.ext.DefaultHandler2
import com.goldin.gcommons.beans.*


/**
 * Various Mojo helper methods
 */
class GMojoUtils
{
    private GMojoUtils ()
    {
    }


    /**
     * Retrieves plugin's {@link Log} instance
     * @return plugin's {@link Log} instance
     */
    static Log getLog () { ThreadLocals.get( Log ) }


    /**
     * Updates Groovy MOP with additional methods
     */
     static mopInit ()
     {
         file() // Triggers GCommons MOP replacements

         /**
          * Trims multi-lines String: each line in the String specified is trim()-ed
          */
         String.metaClass.trimMultiline ={->
             (( String ) delegate ).readLines()*.trim().join( constants().CRLF )
         }


         /**
          * Deletes empty lines from the String
          */
         String.metaClass.deleteEmptyLines ={->
             (( String ) delegate ).readLines().findAll{ it.trim() }.join( constants().CRLF )
         }


         /**
          * Replaces {..} expressions, not preceded by $, by ${..} to work around
          * "Disabling/escaping POM interpolation for specific variable" - http://goo.gl/NyEq
          *
          * We're putting {..} in POMs where we would like to have un-interpolated ${..}
          */
         String.metaClass.addDollar = {->
             // Adding a '$' before {..} where there was no '$' previously
             delegate.replaceAll( /(?<!\$)(?=\{.+?\})/, '\\$' )
         }
     }


    /**
     * Retrieves an instance of {@code OutputStream} ignoring everything that is written to it.
     * @return instance of {@code OutputStream} ignoring everything that is written to it
     */
    static OutputStream devNullOutputStream () { new OutputStream() {
        @Override
        @SuppressWarnings( 'EmptyMethod' )
        void write( int b ) {}
    }}


    /**
    * Retrieves {@link SimpleTemplateEngine} for the resource specified
    */
    static Template getTemplate ( String templatePath, ClassLoader loader = GMojoUtils.classLoader )
    {
        URL    templateURL = GMojoUtils.getResource( templatePath )
        assert templateURL, "[${ templatePath }] could not be loaded from the classpath"

        verify().notNull( new SimpleTemplateEngine( loader ).createTemplate( templateURL ))
    }


   /**
    * {@code GMojoUtils.getTemplate().make().toString()} wrapper
    */
    static String makeTemplate( String  templatePath,
                                Map     binding,
                                String  endOfLine        = null,
                                boolean deleteEmptyLines = false )
    {
        def content = getTemplate( templatePath ).make( binding ).toString()

        if ( endOfLine        ) { content = content.replaceAll( /\r?\n/, (( 'windows' == endOfLine ) ? '\r\n' : '\n' )) }
        if ( deleteEmptyLines ) { content = content.deleteEmptyLines() }

        verify().notNullOrEmpty( content )
    }



    /**
     * Retrieves Maven version as appears in "pom.properties" inside Maven jar.
     *
     * @return Maven version
     */
    static String mavenVersion()
    {
        InputStream is    = verify().notNull( Maven.getResourceAsStream( '/META-INF/maven/org.apache.maven/maven-core/pom.properties' ))
        Properties  props = new Properties()
        props.load( is )
        is.close()
        verify().notNullOrEmpty( props.getProperty( 'version', 'Unknown' ).trim())
    }


    /**
     * Determines if execution continues.
     *
     * @param s {@code <runIf>} string
     * @return true if 'runIf' is not defined or evaluates to true,
     *         false otherwise
     */
    static boolean runIf( String s )
    {
        boolean run = true

        if ( s )
        {
            run = Boolean.valueOf( eval( s, String ))
            log.info( "<runIf>: [$s] evaluated to [$run] - ${ run ? 'continuing' : 'returning' }" )
        }

        run
    }


    /**
     * Evaluates Groovy expression provided and casts it to the class specified.
     *
     * @param expression   Groovy expression to evaluate, if null or empty - null is returned
     * @param resultType   result's type,
     *                     if <code>null</code> - no verification is made for result's type and <code>null</code>
     *                     value is allowed to be returned from eval()-ing the expression
     * @param groovyConfig {@link com.goldin.gcommons.util.GroovyConfig} object to use, allowed to be <code>null</code>
     * @param verbose      Whether Groovy evaluation should be verbose
     *
     * @param <T>        result's type
     * @return           expression evaluated and casted to the type specified
     *                   (after verifying compliance with {@link Class#isInstance(Object)}
     */
    static <T> T eval ( String       expression,
                        Class<T>     resultType = null,
                        GroovyConfig config     = new GroovyConfig(),
                        Object ...   bindingObjects )
    {


        MavenProject project    = ThreadLocals.get( MavenProject )
        MavenSession session    = ThreadLocals.get( MavenSession )
        Map          bindingMap = [ project      : project,
                                    session      : session,
                                    mavenVersion : mavenVersion(),
                                    *:( project.properties + session.userProperties + session.executionProperties )]

        groovy().eval( expression,
                       resultType,
                       groovy().binding( bindingMap, bindingObjects ),
                       config )
    }


    /**
     * Converts an ['a', 'b', 'c'] collection to:
     *  * [a]
     *  * [b]
     *  * [c]
     *
     * @param c Collection to convert
     * @return String to use for log messages
     */
    static String stars ( Collection c ) { "* [${ c.join( "]${ constants().CRLF }* [") }]" }


    /**
     * {@link ArtifactFactory#createBuildArtifact} wrapper
     */
    static Artifact buildArtifact( String groupId, String artifactId, String version, String type )
    {
        assert groupId && artifactId && version && type
        ThreadLocals.get( ArtifactFactory ).createBuildArtifact( groupId, artifactId, version, type )
    }


    /**
     * Retrieves all artifacts from the scopes specified.
     */
    static Set<Artifact> getArtifacts ( Collection<Artifact> initialArtifacts, String ... scopes )
    {
        assert initialArtifacts && scopes

        Set<Artifact> result  = [] as Set
        MavenProject  project = ThreadLocals.get( MavenProject )

        for ( scope in scopes )
        {
            result.addAll( ThreadLocals.get( ArtifactResolver ).resolveTransitively(
                           initialArtifacts as Set,
                           buildArtifact( project.groupId, project.artifactId, project.version, project.packaging ),
                           project.managedVersionMap,
                           ThreadLocals.get( MavenSession ).localRepository,
                           project.remoteArtifactRepositories,
                           ThreadLocals.get( ArtifactMetadataSource ),
                           new ScopeArtifactFilter( verify().notNullOrEmpty( scope ))).artifacts )
        }

        result
    }


    /**
     * Validates content of the file specified to be XML-valid with {@link DefaultHandler2}.
     * @param configFile file to validate
     * @return same file object, for further chain calls
     * @throws RuntimeException if content validation fails
     */
    static File validate ( File configFile )
    {
        for ( parserClass in [ XmlParser, XmlSlurper ] )
        {
            def parser = parserClass.newInstance( true, true )
            parser.setErrorHandler( new DefaultHandler2())
            try
            {
                assert parser.parse( configFile )
            }
            catch ( e )
            {
                throw new MojoExecutionException( "Failed to validate [${ configFile.canonicalPath }]", e )
            }
        }

        configFile
    }


    /**
     * Initializes {@link ThreadLocals} storage for testing environment
     */
    static void initTestThreadLocals()
    {
        ThreadLocals.set( new MavenProject(),
                          new MavenSession( null, null, null, null, null, null, null, new Properties(), new Properties(), new Date()),
                          new DefaultLog( new ConsoleLogger( Logger.LEVEL_DEBUG, 'TestLog' )))
        mopInit()
    }


    /**
     * Retrieves maximal length of map's key.
     */
    static int maxKeyLength ( Map<?, ?> map ) { map.keySet().max{ it.toString().size() }.toString().size() }


    /**
     * Sets property specified to maven project and session provided.
     *
     * @param name       name of the property to set
     * @param value      value of the property to set
     * @param logMessage log message to use when property is set, instead of the default one
     * @param verbose    whether property value set is logged or hidden
     * @param padName    number of characters to pad the property name
     */
    static void setProperty( String name, String value, String logMessage = '', boolean verbose = true, int padName = 0 )
    {
        assert name && ( value != null )

        MavenProject project = ThreadLocals.get( MavenProject )
        MavenSession session = ThreadLocals.get( MavenSession )

        [ project.properties, session.executionProperties, session.userProperties ]*.setProperty( name, value )

        log.info( logMessage ?: '>> Maven property ' +
                                "\${$name}".padRight( padName + 3 ) +
                                ' is set' + ( verbose ? " to \"$value\"" : '' ))
    }


    /**
     *
     * Copies source file to destination applying replacements and filtering.
     *
     * @param sourceFile      source file to copy
     * @param destinationFile destination file to copy the source to,
     *                        <code><b>scp://user:password@host:location</b></code> URLs are supported
     * @param skipIdentical   whether identical files should be skipped (not copied)
     * @param replaces        replacements to make
     * @param filtering       whether Maven
     *                        <a href="http://www.sonatype.com/books/maven-book/reference/resource-filtering-sect-description.html">filtering</a>
     *                        should be performed
     * @param encoding        Filtering/replacement encoding
     * @param fileFilter      {@link MavenFileFilter} instance, allowed to be <code>null</code> if <code>filter</code> is <code>false</code>
     * @param verbose         whether information is written to log with "INFO" level
     * @param move            whether file should be moved and not copied
     *
     * @return destinationFile if file was copied,
     *         null            if file was skipped (identical)
     */
    static File copyFile ( File            sourceFile,
                           File            destinationFile,
                           boolean         skipIdentical,
                           Replace[]       replaces,
                           boolean         filtering,
                           String          encoding,
                           MavenFileFilter fileFilter,
                           boolean         verbose,
                           boolean         move )
    {
        verify().file( sourceFile )
        verify().notNull( destinationFile, replaces )
        verify().notNullOrEmpty( encoding )

        MavenProject mavenProject = ThreadLocals.get( MavenProject )
        MavenSession mavenSession = ThreadLocals.get( MavenSession )
        File         fromFile     = sourceFile
        List<File>   deleteFiles  = []

        try
        {
            if ( filtering )
            {
                verify().notNull( fileFilter, mavenProject, mavenSession )

                /**
                 * http://maven.apache.org/shared/maven-filtering/apidocs/index.html
                 */

                File                  tempFile = file().tempFile()
                List<MavenFileFilter> wrappers = fileFilter.getDefaultFilterWrappers( mavenProject,
                                                                                      null,
                                                                                      false,
                                                                                      mavenSession,
                                                                                      new MavenResourcesExecution())

                fileFilter.copyFile( fromFile, tempFile, true, wrappers, encoding, true )

                if ( verbose )
                {
                    log.info( "[$fromFile.canonicalPath] copied to [$tempFile.canonicalPath] (with <filtering>)" )
                }

                deleteFiles << tempFile
                fromFile = tempFile
            }

            if ( replaces )
            {
                String data = fromFile.getText( encoding )

                for ( replace in replaces )
                {
                    data = replace.replace( data, fromFile.canonicalPath )
                }

                File tempFile = file().tempFile()
                tempFile.write( data, encoding )

                if ( verbose )
                {
                    log.info( "[$fromFile.canonicalPath] copied to [$tempFile.canonicalPath] (with <replaces>)" )
                }

                deleteFiles << tempFile
                fromFile = tempFile
            }

            if ( skipIdentical )
            {
                boolean identicalFiles = (( destinationFile.file )                               &&
                                          ( destinationFile.length()       == fromFile.length()) &&
                                          ( destinationFile.lastModified() == fromFile.lastModified()))
                if ( identicalFiles )
                {
                    log.info( "[$fromFile.canonicalPath] skipped - identical to [$destinationFile.canonicalPath]" )
                    return null
                }
            }

            copy( fromFile, destinationFile, verbose, move )
            if ( move && sourceFile.exists()) { deleteFiles << sourceFile }
            file().delete( *deleteFiles )

            destinationFile
        }
        catch ( e )
        {
            throw new MojoExecutionException( "Failed to copy [$sourceFile.canonicalPath] to [$destinationFile.canonicalPath]",
                                              e )
        }
    }


    /**
     * Copies file to the destination file specified.
     *
     * @param sourceFile      source file to copy
     * @param destinationFile destination file to copy the source to,
     * @param verbose         verbose logging
     * @param verbose         whether file should be moved and not copied
     */
    private static void copy ( File sourceFile, File destinationFile, boolean verbose, boolean move )
    {
        verify().file( sourceFile )
        verify().notNull( destinationFile )
        assert ! net().isNet( destinationFile.path )

        String sourceFilePath      = sourceFile.canonicalPath
        String destinationFilePath = destinationFile.canonicalPath
        String operationName       = ( move ? 'moved' : 'copied' )

        if ( sourceFilePath == destinationFilePath )
        {
            log.warn( "Source [$sourceFilePath] and destination [$destinationFilePath] are the same. File is not $operationName." )
            return
        }

        file().delete( destinationFile )
        file().mkdirs( destinationFile.parentFile )

        if ( ! ( move && sourceFile.renameTo( destinationFile )))
        {
            file().copy( sourceFile, destinationFile.parentFile, destinationFile.name )
        }

        if ( move    ) { file().delete( sourceFile ) }
        if ( verbose ) { log.info( "[$sourceFilePath] $operationName to [$destinationFilePath]" )}
    }


    /**
     * Invokes "maven-deploy-plugin" to deploy the file specified.
     *
     * @param f          file to deploy
     * @param url        Maven repository URL
     * @param groupId    groupId
     * @param artifactId artifactId
     * @param version    version
     * @param classifier classifier, can be <code>null</code>
     * @param project    Maven project
     * @param session    Maven session
     * @param manager    Maven plugin manager
     */
    static void deploy ( File f, String url, String groupId, String artifactId, String version, String classifier,
                         PluginManager manager )
    {
        verify().file( f )
        verify().notNullOrEmpty( url, groupId, artifactId, version )
        assert mavenVersion().startsWith( '2' ): \
               'Right now <deploy> is only supported by Maven 2, see http://evgeny-goldin.org/youtrack/issue/pl-258'

        List<Element> config = [ element( 'file',       f.canonicalPath ),
                                 element( 'url',        url         ),
                                 element( 'groupId',    groupId     ),
                                 element( 'artifactId', artifactId  ),
                                 element( 'version',    version     ),
                                 element( 'packaging',  file().extension( f )) ]
        if ( classifier )
        {
            config << element( 'classifier', classifier )
        }

        String description =
            "[$f.canonicalPath] to [$url] as [<$groupId>:<$artifactId>:<$version>${ classifier ? ':<' + classifier + '>' : '' }]"

        try
        {
            executeMojo( plugin( 'org.apache.maven.plugins',
                                 'maven-deploy-plugin',
                                 '2.7' ),
                         goal( 'deploy-file' ),
                         configuration( config as Element[] ),
                         executionEnvironment( ThreadLocals.get( MavenProject ), ThreadLocals.get( MavenSession ), manager ))

            log.info( "Deployed $description" )
        }
        catch ( e )
        {
            throw new MojoExecutionException( "Failed to deploy $description", e )
        }
    }


    /**
     * Splits a delimiter-separated String.
     *
     * @param s     String to split
     * @param delim delimiter regex expression to split the String with
     * @return result of {@code s.split( delim )*.trim().grep()}
     */
    static List<String> split( String s, String delim = ',' ) { s.split( delim )*.trim().grep() as List }


    /**
     * Add a '$' character to {..} expressions.
     *
     * @param value value containing {..} expressions.
     * @param addDollar if "false" or Groovy Truth false - no changes are made to the value,
     *                  if "true" - all {..} expressions are converted to ${..}
     *                  if list of comma-separated tokens - only {token} expressions are updated
     * @return value modified according to 'addDollar'
     */
    static String addDollar( String value, String addDollar )
    {
        if ( value && addDollar && ( 'false' != addDollar ))
        {
            String pattern = ( 'true' == addDollar ) ? '.+?' : split( addDollar ).collect{ String token -> "\\Q$token\\E" }.join( '|' )
            value          = value.replaceAll( ~/(?<!\$)(?=\{($pattern)\})/, '\\$' )
        }

        value
    }


    static ConstantsBean constants (){ GCommons.constants ()}
    static GeneralBean   general   (){ GCommons.general   ()}
    static FileBean      file      (){ GCommons.file      ()}
    static NetBean       net       (){ GCommons.net       ()}
    static IOBean        io        (){ GCommons.io        ()}
    static VerifyBean    verify    (){ GCommons.verify    ()}
    static GroovyBean    groovy    (){ GCommons.groovy    ()}
}