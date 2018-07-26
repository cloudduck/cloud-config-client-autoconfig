package io.github.piszmog.cloudconfigclient.autoconfig.env;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.javaprop.JavaPropsMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.piszmog.cloudconfig.ConfigException;
import io.github.piszmog.cloudconfig.client.impl.FileConfigClient;
import io.github.piszmog.cloudconfigclient.autoconfig.env.model.Resource;
import io.github.piszmog.cloudconfigclient.autoconfig.env.support.MapFlattener;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Property source locator. Locates the JSON files to be loaded as property sources.
 * <p>
 * Created by Piszmog on 7/21/2018
 */
public class ConfigPropertySourceLocator
{
    private static final transient Log logger = LogFactory.getLog( ConfigPropertySourceLocator.class );

    // ============================================================
    // Class Constants:
    // ============================================================

    private static final String PROPERTY_SOURCE_NAME = "jsonResource";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper( new YAMLFactory() );
    private static final JavaPropsMapper PROPERTIES_MAPPER = new JavaPropsMapper();

    // ============================================================
    // Class Attributes:
    // ============================================================

    private final FileConfigClient fileConfigClient;
    private final ExecutorService executorService = Executors.newFixedThreadPool( 10 );

    // ============================================================
    // Constructors:
    // ============================================================

    /**
     * Creates a new instance of the locator.
     *
     * @param fileConfigClient the file config client to load files from the config server.
     */
    public ConfigPropertySourceLocator( final FileConfigClient fileConfigClient )
    {
        this.fileConfigClient = fileConfigClient;
    }

    // ============================================================
    // Public Methods:
    // ============================================================

    /**
     * Locates the JSON resources to load as property sources.
     *
     * @param resources the resources to load
     * @return The property source with the JSON resources loaded.
     */
    public PropertySource<?> locateConfigResources( final List<Resource> resources )
    {
        List<Future<?>> futures = new ArrayList<>();
        CompositePropertySource composite = new CompositePropertySource( PROPERTY_SOURCE_NAME );
        for ( Resource resource : resources )
        {
            final String directoryPath = resource.getDirectory();
            final List<String> files = resource.getFiles();
            getJsonConfiguration( composite, directoryPath, files, futures );
        }
        try
        {
            for ( Future<?> future : futures )
            {
                future.get();
            }
        }
        catch ( InterruptedException | ExecutionException e )
        {
            throw new ConfigResourceException( "Failed to retrieve file.", e );
        }
        finally
        {
            executorService.shutdownNow();
        }
        return composite;
    }

    // ============================================================
    // Private Methods:
    // ============================================================

    private void getJsonConfiguration( final CompositePropertySource composite, final String directoryPath, final List<String> files, final List<Future<?>> futures )
    {
        for ( String fileName : files )
        {
            futures.add( executorService.submit( () -> loadConfigurationFile( composite, directoryPath, fileName ) ) );
        }
    }

    @SuppressWarnings( "unchecked" )
    private void loadConfigurationFile( final CompositePropertySource composite, final String directoryPath, final String fileName )
    {
        final String filePath = getFilePath( directoryPath, fileName );
        Map file;
        try
        {
            logger.info( "Loading configuration " + filePath + "..." );
            if ( StringUtils.endsWithIgnoreCase( fileName, ".yml" ) || StringUtils.endsWithIgnoreCase( fileName, ".yaml" ) )
            {
                file = getYAMLFile( directoryPath, fileName, filePath );
            }
            else if ( StringUtils.endsWithIgnoreCase( fileName, ".properties" ) )
            {
                file = getPropertiesFile( directoryPath, fileName, filePath );
            }
            else
            {
                file = getJSONFile( directoryPath, fileName );
            }
        }
        catch ( ConfigException e )
        {
            throw new ConfigResourceException( "Failed to load " + filePath, e );
        }
        if ( !file.isEmpty() )
        {
            final Map flattenMap = MapFlattener.flatten( file );
            composite.addPropertySource( new MapPropertySource( filePath, flattenMap ) );
        }
    }

    private String getFilePath( final String directoryPath, final String fileName )
    {
        return directoryPath + "/" + fileName;
    }

    private Map getYAMLFile( final String directoryPath, final String fileName, final String filePath ) throws ConfigException
    {
        final Map file;
        final String yamlFile = fileConfigClient.getFileFromDefaultBranch( fileName, directoryPath, String.class );
        try
        {
            file = YAML_MAPPER.readValue( yamlFile, Map.class );
        }
        catch ( IOException e )
        {
            throw new ConfigResourceException( "Failed to convert " + filePath + " from YAML format to be loaded in the property sources.", e );
        }
        return file;
    }

    private Map getPropertiesFile( final String directoryPath, final String fileName, final String filePath ) throws ConfigException
    {
        final Map file;
        try
        {
            file = PROPERTIES_MAPPER.readValue( fileConfigClient.getFileFromDefaultBranch( fileName, directoryPath, String.class ), Map.class );
        }
        catch ( IOException e )
        {
            throw new ConfigResourceException( "Failed to convert " + filePath + " from PROPERTIES format to be loaded in the property sources.", e );
        }
        return file;
    }

    private Map getJSONFile( final String directoryPath, final String fileName ) throws ConfigException
    {
        return fileConfigClient.getFileFromDefaultBranch( fileName, directoryPath, Map.class );
    }
}
