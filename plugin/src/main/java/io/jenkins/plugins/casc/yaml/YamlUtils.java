package io.jenkins.plugins.casc.yaml;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.model.Mapping;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang.StringUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.composer.Composer;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.resolver.Resolver;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */

public final class YamlUtils {

    public static final Logger LOGGER = Logger.getLogger(ConfigurationAsCode.class.getName());

    public static Node merge(List<YamlSource> sources, ConfigurationContext context) throws ConfiguratorException {
        Node root = null;
        MergeStrategy mergeStrategy = MergeStrategyFactory
            .getMergeStrategyOrDefault(context.getMergeStrategy());
        for (YamlSource<?> source : sources) {
            try (Reader reader = reader(source)) {
                final Node node = read(source, reader, context);

                if (root == null) {
                    root = node;
                } else {
                    if (node != null) {
                        mergeStrategy.merge(root, node, source.toString());
                    }
                }
            } catch (IOException io) {
                throw new ConfiguratorException("Failed to read " + source, io);
            }
        }

        return root;
    }

    public static Node read(YamlSource source, Reader reader, ConfigurationContext context)
        throws IOException {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setMaxAliasesForCollections(context.getYamlMaxAliasesForCollections());
        Composer composer = new Composer(
            new ParserImpl(new StreamReaderWithSource(source, reader)),
            new Resolver(),
            loaderOptions);
        try {
            return composer.getSingleNode();
        } catch (YAMLException e) {
            if (e.getMessage()
                .startsWith("Number of aliases for non-scalar nodes exceeds the specified max")) {
                throw new ConfiguratorException(String.format(
                    "%s%nYou can increase the maximum by setting an environment variable or property%n  ENV: %s=\"100\"%n  PROPERTY: -D%s=\"100\"",
                    e.getMessage(), ConfigurationContext.CASC_YAML_MAX_ALIASES_ENV,
                    ConfigurationContext.CASC_YAML_MAX_ALIASES_PROPERTY));
            }
            throw e;
        }
    }

    public static Reader reader(YamlSource<?> source) throws IOException {
        Object src = source.source;
        if (src instanceof String) {
            final URL url = URI.create((String) src).toURL();
            return new InputStreamReader(addAuthInfo(url.openConnection()).getInputStream(), UTF_8);
        } else if (src instanceof InputStream) {
            return new InputStreamReader((InputStream) src, UTF_8);
        } else if (src instanceof HttpServletRequest) {
            return new InputStreamReader(((HttpServletRequest) src).getInputStream(), UTF_8);
        } else if (src instanceof Path) {
            return Files.newBufferedReader((Path) src);
        }
        throw new IOException(String.format("Unknown %s", source));
    }

    static URLConnection addAuthInfo(URLConnection connection) {
        String token = System.getenv(ConfigurationAsCode.CASC_JENKINS_CONFIG_TOKEN_ENV);
        if (StringUtils.isNotBlank(token)) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
            return connection;
        }

        String user = System.getenv(ConfigurationAsCode.CASC_JENKINS_CONFIG_USER_ENV);
        String password = System.getenv(ConfigurationAsCode.CASC_JENKINS_CONFIG_PASSWORD_ENV);
        if (StringUtils.isNotBlank(user) && StringUtils.isNotBlank(password)) {
            String encoded = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(Charset.forName("UTF-8")));
            connection.setRequestProperty("Authorization", "Basic " + encoded);
            return connection;
        }

        return connection;
    }

    /**
     * Load configuration-as-code model from a set of Yaml sources, merging documents
     */
    public static Mapping loadFrom(List<YamlSource> sources, ConfigurationContext context) throws ConfiguratorException {
        if (sources.isEmpty()) {
            return Mapping.EMPTY;
        }
        final Node merged = merge(sources, context);
        if (merged == null) {
            LOGGER.warning("configuration-as-code yaml source returned an empty document.");
            return Mapping.EMPTY;
        }
        return loadFrom(merged);
    }

    /**
     * Load configuration-as-code model from a snakeyaml Node
     */
    private static Mapping loadFrom(Node node) {
        final ModelConstructor constructor = new ModelConstructor();
        constructor.setComposer(new Composer(null, null) {

            @Override
            public Node getSingleNode() {
                return node;
            }
        });
        return (Mapping) constructor.getSingleData(Mapping.class);
    }
}
