/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB Inc.
 *
 * This file contains original code and/or modifications of original code.
 * Any modifications made by VoltDB Inc. are licensed under the following
 * terms and conditions:
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
/* Copyright (C) 2008 by H-Store Project
 * Brown University
 * Massachusetts Institute of Technology
 * Yale University
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
  
#include <sstream>
#include "tablefactory.h"
#include "common/executorcontext.hpp"
#include "common/debuglog.h"
#include "common/tabletuple.h"
#include "common/types.h"
#include "storage/table.h"
#include "storage/persistenttable.h"
#include "storage/mmap_persistenttable.h"
#include "storage/streamedtable.h"
#include "storage/temptable.h"
#include "storage/WindowTable.h"
#include "streaming/TupleWindow.h"
#include "streaming/TimeWindow.h"
#include "triggers/trigger.h"
#include "indexes/tableindexfactory.h"

#ifdef ANTICACHE
#include "anticache/EvictedTable.h"
#endif

namespace voltdb {

Table* TableFactory::getPersistentTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly)
{
    std::vector<TableIndexScheme> dummy;
    std::vector<Trigger*>* dummyTrig = NULL;
    return getPersistentTable(databaseId, ctx, name,
                              schema, columnNames, dummy, dummyTrig, partitionColumn,
                              exportEnabled, exportOnly);
}

Table* TableFactory::getPersistentTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const TableIndexScheme &pkey_index,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly)
{
    std::vector<TableIndexScheme> dummy;
    std::vector<Trigger*>* dummyTrig = NULL;
    return getPersistentTable(databaseId, ctx, name, schema, columnNames,
                              pkey_index, dummy, dummyTrig, partitionColumn,
                              exportEnabled, exportOnly);
}

Table* TableFactory::getPersistentTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const std::vector<TableIndexScheme> &indexes,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly)
{
	std::vector<Trigger*>* dummyTrig = NULL;
	return getPersistentTable(databaseId, ctx, name, schema, columnNames, indexes,
							dummyTrig, partitionColumn, exportEnabled, exportOnly);
}

Table* TableFactory::getPersistentTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const std::vector<TableIndexScheme> &indexes,
            std::vector<Trigger*> *triggers,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly)
{
    Table *table = NULL;

    if (exportOnly) {
        table = new StreamedTable(ctx, exportEnabled);
        TableFactory::initCommon(databaseId, table, name, schema, columnNames, true);
    }
    else {
        /**
         *  Choosing whether to use MMAP_PersistentTable
         */

        if(!ctx->isMMAPEnabled())
            table = new PersistentTable(ctx, name, exportEnabled);
        else
            table = new MMAP_PersistentTable(ctx, name, exportEnabled);

        VOLT_WARN("MMAP Enabled : %d \n", (int)ctx->isMMAPEnabled());
        
        PersistentTable *pTable = dynamic_cast<PersistentTable*>(table);
        TableFactory::initCommon(databaseId, pTable, name, schema, columnNames, true);
        pTable->m_indexCount = (int)indexes.size();
        pTable->m_indexes = new TableIndex*[indexes.size()];
        pTable->m_partitionColumn = partitionColumn;
        pTable->addAllTriggers(triggers);

        //if(triggers.size() > 0)
        if(triggers != NULL)
        	pTable->m_hasTriggers = true;
        else
        	pTable->m_hasTriggers = false;
        pTable->m_fireTriggers = pTable->m_hasTriggers;

        for (int i = 0; i < indexes.size(); ++i) {
            pTable->m_indexes[i] = TableIndexFactory::getInstance(indexes[i]);
        }
        initConstraints(pTable);
    }

    // initialize stats for the table
    configureStats(databaseId, ctx, name, table);
    return dynamic_cast<Table*>(table);
}

Table* TableFactory::getPersistentTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const TableIndexScheme &pkeyIndex,
            const std::vector<TableIndexScheme> &indexes,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly)
{
	std::vector<Trigger*>* dummyTrig = NULL;
	return getPersistentTable(databaseId, ctx, name, schema, columnNames, pkeyIndex,
							indexes, dummyTrig, partitionColumn, exportEnabled, exportOnly);
}


Table* TableFactory::getPersistentTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const TableIndexScheme &pkeyIndex,
            const std::vector<TableIndexScheme> &indexes,
            std::vector<Trigger*>* triggers,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly)
{
    Table *table = NULL;

    if (exportOnly) {
        table = new StreamedTable(ctx, exportEnabled);
        TableFactory::initCommon(databaseId, table, name, schema, columnNames, true);
    }
    else {
        /**
         *  Choosing whether to use MMAP_PersistentTable
         */

        if(!ctx->isMMAPEnabled())
            table = new PersistentTable(ctx, name, exportEnabled);
        else
            table = new MMAP_PersistentTable(ctx, name, exportEnabled);

        VOLT_DEBUG("MMAP Enabled : %d \n", (int)ctx->isMMAPEnabled());

        PersistentTable *pTable = dynamic_cast<PersistentTable*>(table);
        pTable->m_pkeyIndex = TableIndexFactory::getInstance(pkeyIndex);
        TableFactory::initCommon(databaseId, pTable, name, schema, columnNames, true);
        pTable->m_partitionColumn = partitionColumn;
        pTable->addAllTriggers(triggers);

        //if(triggers.size() > 0)
        if(triggers != NULL)
			pTable->m_hasTriggers = true;
		else
			pTable->m_hasTriggers = false;
        pTable->m_fireTriggers = pTable->m_hasTriggers;

        // one for pkey + all the other indexes
        pTable->m_indexCount = 1 + (int)indexes.size();
        pTable->m_indexes = new TableIndex*[1 + indexes.size()];
        pTable->m_indexes[0] = pTable->m_pkeyIndex;

        for (int i = 0; i < indexes.size(); ++i) {
            pTable->m_indexes[i + 1] = TableIndexFactory::getInstance(indexes[i]);
        }
        initConstraints(pTable);
    }

    configureStats(databaseId, ctx, name, table);

    return dynamic_cast<Table*>(table);
}

#ifdef ANTICACHE
Table* TableFactory::getEvictedTable(voltdb::CatalogId databaseId,
        ExecutorContext *ctx,
        const std::string &name,
        TupleSchema* schema,
        const std::string* columnNames) {
    VOLT_DEBUG("Creating %s", name.c_str());
    Table *table = new EvictedTable(ctx);
    EvictedTable *pTable = dynamic_cast<EvictedTable*>(table);
    pTable->m_indexCount = 0;

    VOLT_DEBUG("Initializing %s common stuff", name.c_str());
    TableFactory::initCommon(databaseId, pTable, name, schema, columnNames, true);

    VOLT_DEBUG("Hooking %s into table stats", name.c_str());
    table->getTableStats()->configure(name + " stats",
            ctx->m_hostId,
            ctx->m_hostname,
            ctx->m_siteId,
            ctx->m_partitionId,
            databaseId);

    return dynamic_cast<Table*>(table);
}
#endif
/**
Table* TableFactory::getWindowTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly,
            int windowSize,
            int slideSize,
            int windowType)
{
    std::vector<TableIndexScheme> dummy;
    std::vector<Trigger*>* dummyTrig = NULL;
    return getWindowTable(databaseId, ctx, name,
                              schema, columnNames, dummy, dummyTrig, partitionColumn,
                              exportEnabled, exportOnly, windowSize, slideSize);
}

Table* TableFactory::getWindowTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const TableIndexScheme &pkey_index,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly,
            int windowSize,
            int slideSize,
            int windowType)
{
    std::vector<TableIndexScheme> dummy;
    std::vector<Trigger*>* dummyTrig = NULL;
    return getWindowTable(databaseId, ctx, name, schema, columnNames,
                              pkey_index, dummy, dummyTrig, partitionColumn,
                              exportEnabled, exportOnly, windowSize, slideSize);
}

Table* TableFactory::getWindowTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const std::vector<TableIndexScheme> &indexes,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly,
            int windowSize,
            int slideSize,
            int windowType)
{
	std::vector<Trigger*>* dummyTrig = NULL;
	return getWindowTable(databaseId, ctx, name, schema, columnNames, indexes,
							dummyTrig, partitionColumn, exportEnabled, exportOnly, windowSize, slideSize);
}

Table* TableFactory::getWindowTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const std::vector<TableIndexScheme> &indexes,
            std::vector<Trigger*> *triggers,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly,
            int windowSize,
            int slideSize,
            int windowType)
{
    Table *table = NULL;

    if (exportOnly) {
        table = new StreamedTable(ctx, exportEnabled);
        TableFactory::initCommon(databaseId, table, name, schema, columnNames, true);
    }
    else {
        table = new WindowTable(ctx, exportEnabled, windowSize, slideSize);
        WindowTable *pTable = dynamic_cast<WindowTable*>(table);
        TableFactory::initCommon(databaseId, pTable, name, schema, columnNames, true);
        pTable->m_indexCount = (int)indexes.size();
        pTable->m_indexes = new TableIndex*[indexes.size()];
        pTable->m_partitionColumn = partitionColumn;
        pTable->addAllTriggers(triggers);

        //if(triggers.size() > 0)
        if(triggers != NULL)
        	pTable->m_hasTriggers = true;
        else
        	pTable->m_hasTriggers = false;
        pTable->m_fireTriggers = false;

        for (int i = 0; i < indexes.size(); ++i) {
            pTable->m_indexes[i] = TableIndexFactory::getInstance(indexes[i]);
        }
        initConstraints(pTable);
    }

    // initialize stats for the table
    configureStats(databaseId, ctx, name, table);
    return dynamic_cast<Table*>(table);
}


Table* TableFactory::getWindowTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const TableIndexScheme &pkeyIndex,
            const std::vector<TableIndexScheme> &indexes,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly,
            int windowSize,
            int slideSize,
            int windowType)
{
	std::vector<Trigger*>* dummyTrig = NULL;
	return getWindowTable(databaseId, ctx, name, schema, columnNames, pkeyIndex,
							indexes, dummyTrig, partitionColumn, exportEnabled, exportOnly, windowSize, slideSize);
}


Table* TableFactory::getWindowTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const TableIndexScheme &pkeyIndex,
            const std::vector<TableIndexScheme> &indexes,
            std::vector<Trigger*>* triggers,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly,
            int windowSize,
            int slideSize,
            int windowType)
{
    Table *table = NULL;

    if (exportOnly) {
        table = new StreamedTable(ctx, exportEnabled);
        TableFactory::initCommon(databaseId, table, name, schema, columnNames, true);
    }
    else {
    	table = new WindowTable(ctx, exportEnabled, windowSize, slideSize);
        WindowTable *pTable = dynamic_cast<WindowTable*>(table);
        pTable->m_pkeyIndex = TableIndexFactory::getInstance(pkeyIndex);
        TableFactory::initCommon(databaseId, pTable, name, schema, columnNames, true);
        pTable->m_partitionColumn = partitionColumn;
        pTable->addAllTriggers(triggers);

        //if(triggers.size() > 0)
        if(triggers != NULL)
			pTable->m_hasTriggers = true;
		else
			pTable->m_hasTriggers = false;
        pTable->m_fireTriggers = false;

        // one for pkey + all the other indexes
        pTable->m_indexCount = 1 + (int)indexes.size();
        pTable->m_indexes = new TableIndex*[1 + indexes.size()];
        pTable->m_indexes[0] = pTable->m_pkeyIndex;

        for (int i = 0; i < indexes.size(); ++i) {
            pTable->m_indexes[i + 1] = TableIndexFactory::getInstance(indexes[i]);
        }
        initConstraints(pTable);
    }

    configureStats(databaseId, ctx, name, table);

    return dynamic_cast<Table*>(table);
}
*/
////////////////////////////////////////////////////////////////////////////////////////
//TODO: These are temporary versions of the window table producers, so as not to break any existing code.
////////////////////////////////////////////////////////////////////////////////////////

Table* TableFactory::getWindowTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly,
            int windowSize,
            int slideSize,
            int windowType)
{
    std::vector<TableIndexScheme> dummy;
    std::vector<Trigger*>* dummyTrig = NULL;
    return getWindowTable(databaseId, ctx, name,
                              schema, columnNames, dummy, dummyTrig, partitionColumn,
                              exportEnabled, exportOnly, windowSize, slideSize, windowType);
}

Table* TableFactory::getWindowTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const TableIndexScheme &pkey_index,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly,
            int windowSize,
            int slideSize,
            int windowType)
{
    std::vector<TableIndexScheme> dummy;
    std::vector<Trigger*>* dummyTrig = NULL;
    return getWindowTable(databaseId, ctx, name, schema, columnNames,
                              pkey_index, dummy, dummyTrig, partitionColumn,
                              exportEnabled, exportOnly, windowSize, slideSize, windowType);
}

Table* TableFactory::getWindowTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const std::vector<TableIndexScheme> &indexes,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly,
            int windowSize,
            int slideSize,
            int windowType)
{
	std::vector<Trigger*>* dummyTrig = NULL;
	return getWindowTable(databaseId, ctx, name, schema, columnNames, indexes,
							dummyTrig, partitionColumn, exportEnabled, exportOnly, windowSize, slideSize, windowType);
}

Table* TableFactory::getWindowTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const std::vector<TableIndexScheme> &indexes,
            std::vector<Trigger*> *triggers,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly,
            int windowSize,
            int slideSize,
            int windowType)
{
    Table *table = NULL;

    if (exportOnly) {
        table = new StreamedTable(ctx, exportEnabled);
        TableFactory::initCommon(databaseId, table, name, schema, columnNames, true);
    }
    else {
    	WindowTableTemp *pTable;
    	if(windowType == TUPLE_WINDOW)
    	{
			table = new TupleWindow(ctx, exportEnabled, windowSize, slideSize);
			pTable = dynamic_cast<TupleWindow*>(table);
    	}
    	else
		{
			table = new TimeWindow(ctx, exportEnabled, windowSize, slideSize);
			pTable = dynamic_cast<TimeWindow*>(table);
		}
    	TableFactory::initCommon(databaseId, pTable, name, schema, columnNames, true);
		pTable->m_indexCount = (int)indexes.size();
		pTable->m_indexes = new TableIndex*[indexes.size()];
		pTable->m_partitionColumn = partitionColumn;
		pTable->addAllTriggers(triggers);
		pTable->initWin();

		//if(triggers.size() > 0)
		if(triggers != NULL)
			pTable->m_hasTriggers = true;
		else
			pTable->m_hasTriggers = false;
		pTable->m_fireTriggers = false;

		for (int i = 0; i < indexes.size(); ++i) {
			pTable->m_indexes[i] = TableIndexFactory::getInstance(indexes[i]);
		}
		initConstraints(pTable);

    }

    // initialize stats for the table
    configureStats(databaseId, ctx, name, table);
    return table;
}

Table* TableFactory::getWindowTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const TableIndexScheme &pkeyIndex,
            const std::vector<TableIndexScheme> &indexes,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly,
            int windowSize,
            int slideSize,
            int windowType)
{
	std::vector<Trigger*>* dummyTrig = NULL;
	return getWindowTable(databaseId, ctx, name, schema, columnNames, pkeyIndex,
							indexes, dummyTrig, partitionColumn, exportEnabled,
							exportOnly, windowSize, slideSize, windowType);
}


Table* TableFactory::getWindowTable(
            voltdb::CatalogId databaseId,
            ExecutorContext *ctx,
            const std::string &name,
            TupleSchema* schema,
            const std::string* columnNames,
            const TableIndexScheme &pkeyIndex,
            const std::vector<TableIndexScheme> &indexes,
            std::vector<Trigger*>* triggers,
            int partitionColumn,
            bool exportEnabled,
            bool exportOnly,
            int windowSize,
            int slideSize,
            int windowType)
{
    Table *table = NULL;

    if (exportOnly) {
        table = new StreamedTable(ctx, exportEnabled);
        TableFactory::initCommon(databaseId, table, name, schema, columnNames, true);
    }
    else {
    	WindowTableTemp* pTable;
    	if(windowType == TUPLE_WINDOW)
    	{
    		table = new TupleWindow(ctx, exportEnabled, windowSize, slideSize);
    		pTable = dynamic_cast<TupleWindow*>(table);
    	}
    	else
    	{
    		table = new TimeWindow(ctx, exportEnabled, windowSize, slideSize);
    		pTable = dynamic_cast<TimeWindow*>(table);
    	}
		pTable->m_pkeyIndex = TableIndexFactory::getInstance(pkeyIndex);
		TableFactory::initCommon(databaseId, pTable, name, schema, columnNames, true);
		pTable->m_partitionColumn = partitionColumn;
		pTable->addAllTriggers(triggers);

		//if(triggers.size() > 0)
		if(triggers != NULL)
			pTable->m_hasTriggers = true;
		else
			pTable->m_hasTriggers = false;
		pTable->m_fireTriggers = false;

		// one for pkey + all the other indexes
		pTable->m_indexCount = 1 + (int)indexes.size();
		pTable->m_indexes = new TableIndex*[1 + indexes.size()];
		pTable->m_indexes[0] = pTable->m_pkeyIndex;
		pTable->initWin();

		for (int i = 0; i < indexes.size(); ++i) {
			pTable->m_indexes[i + 1] = TableIndexFactory::getInstance(indexes[i]);
		}
		initConstraints(pTable);

    }

    configureStats(databaseId, ctx, name, table);

    return table;
}

//////////////////////////////////////////////////////////////////////////////
//END
//////////////////////////////////////////////////////////////////////////////



TempTable* TableFactory::getTempTable(
        const voltdb::CatalogId databaseId,
        const std::string &name,
        TupleSchema* schema,
        const std::string* columnNames,
        int* tempTableMemoryInBytes) {

    TempTable* table = new TempTable();
    TableFactory::initCommon(databaseId, table, name, schema, columnNames, true);
    table->m_tempTableMemoryInBytes = tempTableMemoryInBytes;
    return table;
}

/**
 * Creates a temp table with the same schema as the provided template table
 */
TempTable* TableFactory::getCopiedTempTable(
        const voltdb::CatalogId databaseId,
        const std::string &name,
        const Table* template_table,
        int* tempTableMemoryInBytes) {

    TempTable* table = new TempTable();
    TableFactory::initCommon(databaseId, table, name, template_table->m_schema, template_table->m_columnNames, false);
    table->m_tempTableMemoryInBytes = tempTableMemoryInBytes;
    return table;
}

void TableFactory::initCommon(
        voltdb::CatalogId databaseId,
        Table *table,
        const std::string &name,
        TupleSchema *schema,
        const std::string *columnNames,
        const bool ownsTupleSchema) {
    table->m_databaseId = databaseId;
    table->m_name = name;
    table->initializeWithColumns(schema, columnNames, ownsTupleSchema);
    assert (table->columnCount() == schema->columnCount());
}

void TableFactory::initConstraints(PersistentTable* table) {

    // count the unique indexes
    table->m_uniqueIndexCount = 0;
    for (int i = 0; i < table->m_indexCount; ++i) {
        TableIndex *index = table->m_indexes[i];
        if (index->isUniqueIndex()) {
            table->m_uniqueIndexCount++;
        }
    }

    if (table->m_uniqueIndexes)
        delete[] table->m_uniqueIndexes;
    table->m_uniqueIndexes = new TableIndex*[table->m_uniqueIndexCount];
    int curIndex = 0;
    if (table->m_pkeyIndex != NULL) {
        table->m_uniqueIndexes[curIndex++] = table->m_pkeyIndex;
    }

    for (int i = 0; i < table->m_indexCount; ++i) {
        TableIndex *index = table->m_indexes[i];
        if ((index->isUniqueIndex()) && (index != table->m_pkeyIndex)) {
            table->m_uniqueIndexes[curIndex++] = index;
        }
    }
}

void TableFactory::configureStats(voltdb::CatalogId databaseId,
        ExecutorContext *ctx,
        std::string name,
        Table *table) {
    std::string hostname = "";
    if(ctx != NULL)
    	hostname = ctx->m_hostname;


	assert(ctx != NULL);
	// initialize stats for the table
	table->getTableStats()->configure(name + " stats",
									  ctx->m_hostId,
									  ctx->m_hostname,
									  ctx->m_siteId,
									  ctx->m_partitionId,
									  databaseId);

	// initialize stats for all the indexes for the table
	std::vector<TableIndex*> tindexes = table->allIndexes();
	for (size_t i = 0; i < tindexes.size(); i++) {
		TableIndex *index = tindexes[i];
		index->getIndexStats()->configure(index->getName() + " stats",
										  table->name(),
										  ctx->m_hostId,
										  ctx->m_hostname,
										  ctx->m_siteId,
										  ctx->m_partitionId,
										  databaseId);
	}

	// initialize stats for all the trigger for the table
	PersistentTable *persistTarget = dynamic_cast<PersistentTable*>(table);
	if(persistTarget != NULL && persistTarget->hasTriggers()) {
		std::vector<Trigger*>::iterator trig_iter;
		for(trig_iter = persistTarget->getTriggers()->begin(); trig_iter != persistTarget->getTriggers()->end(); trig_iter++)
		{
			(*trig_iter)->getTriggerStats()->configure((*trig_iter)->name() + " stats",
					  ctx->m_hostId,
					  ctx->m_hostname,
					  ctx->m_siteId,
					  ctx->m_partitionId,
					  databaseId);
		}

		// FIXME: we need isStream() for Table, not use hasTriggers() to determine if it is a stream
		// by hawk, initialize stats for all the stream
		persistTarget->getStreamStats()->configure(name + "stream stats",
										  ctx->m_hostId,
										  ctx->m_hostname,
										  ctx->m_siteId,
										  ctx->m_partitionId,
										  databaseId);
	}
}

}

