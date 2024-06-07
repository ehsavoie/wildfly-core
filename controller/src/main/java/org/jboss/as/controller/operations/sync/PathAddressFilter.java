/*
 * Copyright 2016 JBoss by Red Hat.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.controller.operations.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;

/**
 * @author Emanuel Muckenhuber (c) 2016 Red Hat, inc.
 */
public interface PathAddressFilter {

    OperationContext.AttachmentKey<PathAddressFilter> KEY = OperationContext.AttachmentKey.create(PathAddressFilter.class);

    boolean accepts(PathAddress address);

    public static final class Builder {
        private boolean accept;
        private List<PathAddress> rejects = new ArrayList<>();

        private Builder() {
        }

        public static Builder create() {
            return new Builder();
        }

        public Builder setAccept(boolean accept) {
            this.accept = accept;
            return this;
        }

        public Builder addReject(PathAddress address) {
            this.rejects.add(address);
            return this;
        }

        public PathAddressFilter build() {
            PathAddressFilterImpl filter = new PathAddressFilterImpl(accept);
            for(PathAddress address: rejects) {
                filter.addReject(address);
            }
            return filter;
        }
    }

    public static class PathAddressFilterImpl implements PathAddressFilter {

        private final boolean accept;
        private final Node node = new Node();

        private PathAddressFilterImpl(boolean accept) {
            this.accept = accept;
        }

        @Override
        public boolean accepts(PathAddress address) {
            final Iterator<PathElement> i = address.iterator();
            Node currentNode = this.node;
            while (i.hasNext()) {
                final PathElement element = i.next();
                final Node key = currentNode.children.get(element.getKey());
                if (key == null) {
                    return currentNode.accept;
                }
                currentNode = key.children.get(element.getValue());
                if (currentNode == null) {
                    currentNode = key.children.get("*");
                }
                if (currentNode == null) {
                    return key.accept;
                }
                if (!i.hasNext()) {
                    return currentNode.accept;
                }
            }
            return accept;
        }

        private void addReject(final PathAddress address) {
            final Iterator<PathElement> i = address.iterator();
            Node currentNode = this.node;
            while (i.hasNext()) {
                final PathElement element = i.next();
                final String elementKey = element.getKey();
                Node key = currentNode.children.get(elementKey);
                if (key == null) {
                    key = new Node();
                    currentNode.children.put(elementKey, key);
                }
                final String elementValue = element.getValue();
                Node value = key.children.get(elementValue);
                if (value == null) {
                    value = new Node();
                    key.children.put(elementValue, value);
                }
                if (!i.hasNext()) {
                    value.accept = false;
                }
            }
        }

        private static class Node {
            private final Map<String, Node> children = new HashMap<>();
            private boolean accept = true;

            Node() {
            }

        }

    }
}
