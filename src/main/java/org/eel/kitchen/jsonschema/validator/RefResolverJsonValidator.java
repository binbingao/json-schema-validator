/*
 * Copyright (c) 2012, Francis Galiegue <fgaliegue@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Lesser GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Lesser GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.eel.kitchen.jsonschema.validator;

import com.fasterxml.jackson.databind.JsonNode;
import org.eel.kitchen.jsonschema.main.JsonSchemaException;
import org.eel.kitchen.jsonschema.main.JsonSchemaFactory;
import org.eel.kitchen.jsonschema.main.SchemaContainer;
import org.eel.kitchen.jsonschema.main.SchemaNode;
import org.eel.kitchen.jsonschema.main.ValidationContext;
import org.eel.kitchen.jsonschema.main.ValidationReport;
import org.eel.kitchen.jsonschema.ref.JsonRef;
import org.eel.kitchen.jsonschema.util.JacksonUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * First validator in the validation chain
 *
 * <p>This validator is in charge of resolving JSON References. In most cases,
 * it will not do anything since most schemas are not JSON References.</p>
 *
 * <p>This is also the class which detects ref loops.</p>
 *
 * <p>Its {@link #next()} method always returns a {@link SyntaxJsonValidator}.
 * </p>
 */
public final class RefResolverJsonValidator
    implements JsonValidator
{
    /**
     * The schema factory
     */
    private final JsonSchemaFactory factory;

    /**
     * The schema node
     */
    private SchemaNode schemaNode;

    public RefResolverJsonValidator(final JsonSchemaFactory factory,
        final SchemaNode schemaNode)
    {
        this.factory = factory;
        this.schemaNode = schemaNode;
    }

    @Override
    public boolean validate(final ValidationContext context,
        final ValidationReport report, final JsonNode instance)
    {
        /*
         * This set will store all ABSOLUTE JSON references we encounter
         * during ref resolution. If there is an attempt to store an already
         * existing reference, this means a ref loop.
         *
         * We want to preserve insertion order, therefore we have to use a
         * LinkedHashSet.
         */
        final Set<JsonRef> refs = new LinkedHashSet<JsonRef>();

        JsonNode node = schemaNode.getNode();

        /*
         * As long as we have a URI in our current node, we need to resolve it.
         * The resolve() method will throw a JsonSchemaException if resolution
         * fails for whatever reason, which is an error condition: validation
         * shoud then stop.
         */
        while (JacksonUtils.nodeIsURI(node.path("$ref")))
            try {
                node = resolve(context, node, refs);
            } catch (JsonSchemaException e) {
                report.addMessage(e.getMessage());
                return false;
            }

        schemaNode = new SchemaNode(context.getContainer(), node);
        return true;
    }

    @Override
    public JsonValidator next()
    {
        return new SyntaxJsonValidator(factory, schemaNode);
    }

    /**
     * Resolve references
     *
     * @param context the validation context
     * @param node the schema node
     * @return the resolved node
     * @throws JsonSchemaException invalid reference, loop detected, or could
     * not get content
     */
    private JsonNode resolve(final ValidationContext context,
        final JsonNode node, final Set<JsonRef> refs)
        throws JsonSchemaException
    {
        SchemaContainer container = context.getContainer();

        /*
         * Calculate the target reference:
         *
         * - grab the locator from the current container (source);
         * - grab the reference from the current node (ref);
         * - calculate the full target reference by resolving ref against
         *   source.
         */
        final JsonRef source = container.getLocator();
        final JsonRef ref = JsonRef.fromString(node.get("$ref").textValue());
        final JsonRef target = source.resolve(ref);

        /*
         * If we have already seen that reference, this is a ref loop.
         */
        if (!refs.add(target))
            throw new JsonSchemaException("$ref problem: ref loop detected: "
                + refs);

        /*
         * If the source JSON Reference does not contain the target,
         * we need to switch containers; grab the new one and update the
         * validation context appropriately.
         *
         * Note that getting the new container may FAIL: in this case,
         * factory.getSchema() will throw an exception which will be handled
         * by the caller.
         */
        if (!source.contains(target)) {
            container = factory.getSchema(target.getRootAsURI());
            context.setContainer(container);
        }

        /*
         * Now, calculate the result node by resolving the target's fragment
         * against the container schema.
         */
        final JsonNode ret
            = target.getFragment().resolve(container.getSchema());

        /*
         * If the fragment does not resolve anywhere, we have a dangling
         * pointer: this is an error condition.
         */
        if (ret.isMissingNode())
            throw new JsonSchemaException("$ref problem: dangling JSON ref "
                + target);

        return ret;
    }
}
