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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.rows;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.db.filter.ColumnFilter;
import org.apache.cassandra.db.transform.Transformation;
import org.apache.cassandra.utils.FBUtilities;

/**
 * Static methods to work with row iterators.
 */
public abstract class RowIterators
{
    private static final Logger logger = LoggerFactory.getLogger(RowIterators.class);

    private RowIterators() {}

    public static void digest(RowIterator iterator, MessageDigest digest, MessageDigest altDigest, Set<ByteBuffer> columnsToExclude)
    {
        // TODO: we're not computing digest the same way that old nodes. This is
        // currently ok as this is only used for schema digest and the is no exchange
        // of schema digest between different versions. If this changes however,
        // we'll need to agree on a version.
        digest.update(iterator.partitionKey().getKey().duplicate());
        iterator.columns().regulars.digest(digest);
        iterator.columns().statics.digest(digest);
        FBUtilities.updateWithBoolean(digest, iterator.isReverseOrder());
        iterator.staticRow().digest(digest);

        if (altDigest != null)
        {
            // Compute the "alternative digest" here.
            altDigest.update(iterator.partitionKey().getKey().duplicate());
            iterator.columns().regulars.digest(altDigest, columnsToExclude);
            iterator.columns().statics.digest(altDigest, columnsToExclude);
            FBUtilities.updateWithBoolean(altDigest, iterator.isReverseOrder());
            iterator.staticRow().digest(altDigest, columnsToExclude);
        }

        while (iterator.hasNext())
        {
            Row row = iterator.next();
            row.digest(digest);
            if (altDigest != null)
                row.digest(altDigest, columnsToExclude);
        }
    }

    /**
     * Filter the provided iterator to only include cells that are selected by the user.
     *
     * @param iterator the iterator to filter.
     * @param filter the {@code ColumnFilter} to use when deciding which cells are queried by the user. This should be the filter
     * that was used when querying {@code iterator}.
     * @return the filtered iterator..
     */
    public static RowIterator withOnlyQueriedData(RowIterator iterator, ColumnFilter filter)
    {
        if (filter.allFetchedColumnsAreQueried())
            return iterator;

        return Transformation.apply(iterator, new WithOnlyQueriedData(filter));
    }

    /**
     * Wraps the provided iterator so it logs the returned rows for debugging purposes.
     * <p>
     * Note that this is only meant for debugging as this can log a very large amount of
     * logging at INFO.
     */
    public static RowIterator loggingIterator(RowIterator iterator, final String id)
    {
        CFMetaData metadata = iterator.metadata();
        logger.info("[{}] Logging iterator on {}.{}, partition key={}, reversed={}",
                    id,
                    metadata.ksName,
                    metadata.cfName,
                    metadata.getKeyValidator().getString(iterator.partitionKey().getKey()),
                    iterator.isReverseOrder());

        class Log extends Transformation
        {
            @Override
            public Row applyToStatic(Row row)
            {
                if (!row.isEmpty())
                    logger.info("[{}] {}", id, row.toString(metadata));
                return row;
            }

            @Override
            public Row applyToRow(Row row)
            {
                logger.info("[{}] {}", id, row.toString(metadata));
                return row;
            }
        }
        return Transformation.apply(iterator, new Log());
    }
}
