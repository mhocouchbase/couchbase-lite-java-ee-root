//
// IndexSpec.cc
//
// Copyright © 2019 Couchbase. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

#include "IndexSpec.hh"
#include "QueryParser+Private.hh"
#include "Error.hh"

namespace litecore {
    using namespace fleece;
    using namespace fleece::impl;


    void IndexSpec::validateName() const {
        if(name.empty()) {
            error::_throw(error::LiteCoreError::InvalidParameter, "Index name must not be empty");
        }
        if(slice(name).findByte((uint8_t)'"') != nullptr) {
            error::_throw(error::LiteCoreError::InvalidParameter, "Index name must not contain "
                          "the double quote (\") character");
        }
    }

    Doc* IndexSpec::doc() const {
        if (!_doc) {
            try {
                _doc = Doc::fromJSON(expressionJSON);
            } catch (const FleeceException &) {
                error::_throw(error::InvalidQuery, "Invalid JSON in index expression");
            }
        }
        return _doc;
    }

    const Array* IndexSpec::what() const {
        const Array *what = nullptr;
        if (auto dict = doc()->asDict(); dict) {
            what = qp::requiredArray(qp::getCaseInsensitive(dict, "WHAT"),
                                     "Index WHAT term");
        } else {
            // For backward compatibility, the JSON is allowed to be just an array
            // of expressions.
            what = qp::requiredArray(doc()->root(), "Index JSON");
        }
        if (what->empty())
            error::_throw(error::InvalidQuery, "Index WHAT list cannot be empty");
        return what;
    }

    const Array* IndexSpec::where() const {
        if (auto dict = doc()->asDict(); dict) {
            if (auto whereVal = qp::getCaseInsensitive(dict, "WHERE"); whereVal)
                return qp::requiredArray(whereVal, "Index WHERE term");
        }
        return nullptr;
    }


}
