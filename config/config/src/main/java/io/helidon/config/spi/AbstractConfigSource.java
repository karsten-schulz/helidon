/*
 * Copyright (c) 2017, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.config.spi;

import java.io.StringReader;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.helidon.common.OptionalHelper;
import io.helidon.common.reactive.Flow;
import io.helidon.config.Config;
import io.helidon.config.internal.ConfigKeyImpl;
import io.helidon.config.internal.ListNodeBuilderImpl;
import io.helidon.config.internal.ObjectNodeBuilderImpl;
import io.helidon.config.spi.ConfigNode.ListNode;
import io.helidon.config.spi.ConfigNode.ObjectNode;
import io.helidon.config.spi.ConfigNode.ValueNode;
import io.helidon.config.spi.ConfigParser.Content;

/**
 * Base abstract implementation of {@link ConfigSource}, suitable for concrete
 * implementations to extend.
 *
 * @param <S> a type of data stamp
 * @see Builder
 */
public abstract class AbstractConfigSource<S> extends AbstractSource<ObjectNode, S> implements ConfigSource {

    private final Function<Config.Key, String> mediaTypeMapping;
    private final Function<Config.Key, ConfigParser> parserMapping;

    private ConfigContext configContext;

    /**
     * Initializes config source from builder.
     *
     * @param builder builder to be initialized from
     */
    protected AbstractConfigSource(Builder<?, ?> builder) {
        super(builder);

        mediaTypeMapping = builder.getMediaTypeMapping();
        parserMapping = builder.getParserMapping();
    }

    @Override
    public final void init(ConfigContext context) {
        configContext = context;
    }

    protected ConfigContext getConfigContext() {
        return configContext;
    }

    @Override
    protected Data<ObjectNode, S> processLoadedData(Data<ObjectNode, S> data) {
        if (!data.data().isPresent()
                || (mediaTypeMapping == null && parserMapping == null)) {
            return data;
        }
        return new Data<>(Optional.of(processObject(data.stamp(), ConfigKeyImpl.of(), data.data().get())), data.stamp());
    }

    private ConfigNode processNode(Optional<S> datastamp, ConfigKeyImpl key, ConfigNode node) {
        switch (node.getNodeType()) {
        case OBJECT:
            return processObject(datastamp, key, (ObjectNode) node);
        case LIST:
            return processList(datastamp, key, (ListNode) node);
        case VALUE:
            return processValue(datastamp, key, (ValueNode) node);
        default:
            throw new IllegalArgumentException("Unsupported node type: " + node.getClass().getName());
        }
    }

    private ObjectNode processObject(Optional<S> datastamp, ConfigKeyImpl key, ObjectNode objectNode) {
        ObjectNodeBuilderImpl builder = (ObjectNodeBuilderImpl) ObjectNode.builder();

        objectNode.forEach((name, node) -> builder.addNode(name, processNode(datastamp, key.child(name), node)));

        return builder.build();
    }

    private ListNode processList(Optional<S> datastamp, ConfigKeyImpl key, ListNode listNode) {
        ListNodeBuilderImpl builder = (ListNodeBuilderImpl) ListNode.builder();

        for (int i = 0; i < listNode.size(); i++) {
            builder.addNode(processNode(datastamp, key.child(Integer.toString(i)), listNode.get(i)));
        }

        return builder.build();
    }

    private ConfigNode processValue(Optional<S> datastamp, Config.Key key, ValueNode valueNode) {
        AtomicReference<ConfigNode> result = new AtomicReference<>(valueNode);
        findParserForKey(key)
                .ifPresent(parser -> result.set(parser.parse(
                        Content.from(new StringReader(valueNode.get()), null, datastamp))));
        return result.get();
    }

    private Optional<ConfigParser> findParserForKey(Config.Key key) {
        return OptionalHelper.from(Optional.ofNullable(parserMapping).map(mapping -> mapping.apply(key)))
                .or(() -> Optional.ofNullable(mediaTypeMapping).map(mapping -> mapping.apply(key))
                        .flatMap(mediaType -> getConfigContext().findParser(mediaType)))
                .asOptional();
    }

    /**
     * {@inheritDoc}
     * <p>
     * All subscribers are notified using specified {@link AbstractSource.Builder#changesExecutor(Executor) executor}.
     */
    @Override
    public final Flow.Publisher<Optional<ObjectNode>> changes() {
        return getChangesPublisher();
    }

    /**
     * A common {@link ConfigSource} builder ready to be extended by builder implementation related to {@link ConfigSource}
     * extensions.
     * <p>
     * It allows to configure following properties:
     * <ul>
     * <li>{@code mediaTypeMapping} - a mapping of a key to a media type</li>
     * <li>{@code parserMapping} - a mapping of a key to a {@link ConfigParser}</li>
     * </ul>
     *
     * @param <B> type of Builder implementation
     * @param <T> type of key source attributes (target) used to construct polling strategy from
     */
    public abstract static class Builder<B extends Builder<B, T>, T>
            extends AbstractSource.Builder<B, T, ConfigSource>
            implements io.helidon.common.Builder<ConfigSource> {

        private static final String MEDIA_TYPE_MAPPING_KEY = "media-type-mapping";
        private final B thisBuilder;
        private Function<Config.Key, String> mediaTypeMapping;
        private Function<Config.Key, ConfigParser> parserMapping;
        private volatile ConfigSource configSource;

        /**
         * Initialize builder.
         *
         * @param targetType target type
         */
        protected Builder(Class<T> targetType) {
            super(targetType);

            this.thisBuilder = (B) this;

            mediaTypeMapping = null;
            parserMapping = null;
        }

        @Override
        public ConfigSource get() {
            if (configSource == null) {
                configSource = build();
            }
            return configSource;
        }

        /**
         * {@inheritDoc}
         * <ul>
         * <li>{@code media-type-mapping} - type {@code Map} - key to media type, see {@link #mediaTypeMapping(Function)}</li>
         * </ul>
         *
         * @param metaConfig configuration properties used to initialize a builder instance.
         * @return modified builder instance
         */
        @Override
        protected B init(Config metaConfig) {
            //media-type-mapping
            metaConfig.get(MEDIA_TYPE_MAPPING_KEY).detach().asOptionalMap()
                    .ifPresent(this::initMediaTypeMapping);

            return super.init(metaConfig);
        }

        private void initMediaTypeMapping(Map<String, String> mediaTypeMapping) {
            mediaTypeMapping(key -> mediaTypeMapping.get(key.toString()));
        }

        /**
         * Sets a function mapping key to media type.
         *
         * @param mediaTypeMapping a mapping function
         * @return a modified builder
         */
        public B mediaTypeMapping(Function<Config.Key, String> mediaTypeMapping) {
            Objects.requireNonNull(mediaTypeMapping, "mediaTypeMapping cannot be null");

            this.mediaTypeMapping = mediaTypeMapping;
            return thisBuilder;
        }

        /**
         * Sets a function mapping key to a parser.
         *
         * @param parserMapping a mapping function
         * @return a modified builder
         */
        public B parserMapping(Function<Config.Key, ConfigParser> parserMapping) {
            Objects.requireNonNull(parserMapping, "parserMapping cannot be null");

            this.parserMapping = parserMapping;
            return thisBuilder;
        }

        protected Function<Config.Key, String> getMediaTypeMapping() {
            return mediaTypeMapping;
        }

        protected Function<Config.Key, ConfigParser> getParserMapping() {
            return parserMapping;
        }

    }

}
