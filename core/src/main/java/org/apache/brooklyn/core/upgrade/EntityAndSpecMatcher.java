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

package org.apache.brooklyn.core.upgrade;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.objs.SpecParameter;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;

public class EntityAndSpecMatcher {

    private final Callback callback;

    public EntityAndSpecMatcher(Callback callback) {
        this.callback = callback;
    }

    public void match(Entity entity, EntitySpec<?> spec) {
        Deque<Entity> outerE = new ArrayDeque<>();
        outerE.add(entity);

        Deque<DecoratedSpec> innerES = new ArrayDeque<>();
        Deque<Deque<DecoratedSpec>> outerES = new ArrayDeque<>();
        innerES.add(new DecoratedSpec(spec, DecoratedSpec.DecoratedSpecKind.ROOT));
        outerES.add(innerES);

        match(outerE, outerES, callback);
    }

    // TODO: specs is too permissive. Use DS.kind() to check parents only.
    // TODO: specs could be Deque<Set<DecoratedSpec>>
    // invariants: ancestry and specs are never empty.
    private void match(Deque<Entity> ancestry, Deque<Deque<DecoratedSpec>> specs, Callback callback) {
        Entity entity = ancestry.peek();
        DecoratedSpec specMatch = match(entity, specs);

        // Specs that were plausibly the basis for entity's children.
        Deque<DecoratedSpec> subSpecs = new ArrayDeque<>();

        if (specMatch != null) {
            callback.onMatch(entity, specMatch.spec());
            specMatch.setMatched();
            subSpecs.addAll(getSubSpecs(specMatch.spec()));
        } else {
            callback.unmatched(entity);
        }

        for (Entity descendant : entity.getChildren()) {
            ancestry.push(descendant);
            // repeatedly popped by recursive call.
            specs.push(subSpecs);
            match(ancestry, specs, callback);
        }

        for (DecoratedSpec subSpec : subSpecs) {
            if (!subSpec.isMatched()) {
                // Don't care about specs that came from parameters or flags that weren't matched.
                // TODO: Make this decision here or in callback?
                if (subSpec.kind().equals(DecoratedSpec.DecoratedSpecKind.CHILD)) {
                    callback.unmatched(subSpec.spec(), entity);
                } else {
                    System.out.println("*** unmatched spec: " + subSpec);
                }
            }
        }

        ancestry.pop();
        specs.pop();
    }

    /** Match a single entity with an entry in specs */
    private DecoratedSpec match(Entity entity, Deque<Deque<DecoratedSpec>> specs) {
        String planId = entity.config().get(BrooklynConfigKeys.PLAN_ID);
        if (planId != null) {
            for (Deque<DecoratedSpec> spec : specs) {
                for (DecoratedSpec ds : spec) {
                    Object specPlanId = ds.spec().getConfig().get(BrooklynConfigKeys.PLAN_ID);
                    if (specPlanId != null && planId.equals(specPlanId)) {
                        return ds;
                    }
                }
            }
        }
        return null;
    }

    /**
     * @return A set containing the children of spec and all EntitySpec parameters and flags.
     */
    private Set<DecoratedSpec> getSubSpecs(EntitySpec<?> spec) {
        Set<DecoratedSpec> specs = new LinkedHashSet<>();

        // Find all the config that's a spec!
        for (SpecParameter<?> parameter : spec.getParameters()) {
            if (parameter.getConfigKey().getTypeToken().isAssignableFrom(EntitySpec.class)) {
                EntitySpec<?> configSpec = null;
                Object defaultValue = parameter.getConfigKey().getDefaultValue();
                if (defaultValue instanceof EntitySpec) {
                    configSpec = (EntitySpec) defaultValue;
                }
                Object configValue = spec.getConfig().get(parameter.getConfigKey());
                if (configValue instanceof EntitySpec) {
                    configSpec = (EntitySpec) configValue;
                }
                if (configSpec != null) {
                    specs.add(new DecoratedSpec(configSpec, DecoratedSpec.DecoratedSpecKind.PARAMETER));
                }
            }
        }

        // Must we support flags?
        for (Object value : spec.getFlags().values()) {
            if (value instanceof EntitySpec) {
                specs.add(new DecoratedSpec((EntitySpec) value, DecoratedSpec.DecoratedSpecKind.FLAG));
            }
        }

        for (EntitySpec<?> childSpec : spec.getChildren()) {
            specs.add(new DecoratedSpec(childSpec, DecoratedSpec.DecoratedSpecKind.CHILD));
        }

        return specs;
    }
}
