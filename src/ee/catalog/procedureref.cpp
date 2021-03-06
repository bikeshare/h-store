/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB Inc.
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

/* WARNING: THIS FILE IS AUTO-GENERATED
            DO NOT MODIFY THIS SOURCE
            ALL CHANGES MUST BE MADE IN THE CATALOG GENERATOR */

#include <cassert>
#include "procedureref.h"
#include "catalog.h"
#include "procedure.h"

using namespace catalog;
using namespace std;

ProcedureRef::ProcedureRef(Catalog *catalog, CatalogType *parent, const string &path, const string &name)
: CatalogType(catalog, parent, path, name)
{
    CatalogValue value;
    m_fields["procedure"] = value;
}

ProcedureRef::~ProcedureRef() {
}

void ProcedureRef::update() {
    m_procedure = m_fields["procedure"].typeValue;
}

CatalogType * ProcedureRef::addChild(const std::string &collectionName, const std::string &childName) {
    return NULL;
}

CatalogType * ProcedureRef::getChild(const std::string &collectionName, const std::string &childName) const {
    return NULL;
}

bool ProcedureRef::removeChild(const std::string &collectionName, const std::string &childName) {
    assert (m_childCollections.find(collectionName) != m_childCollections.end());
    return false;
}

const Procedure * ProcedureRef::procedure() const {
    return dynamic_cast<Procedure*>(m_procedure);
}

