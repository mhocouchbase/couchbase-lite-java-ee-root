//
// PredictiveModel.java
//
// Copyright (c) 2019 Couchbase, Inc.  All rights reserved.
//
// Licensed under the Couchbase License Agreement (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// https://info.couchbase.com/rs/302-GJY-034/images/2017-10-30_License_Agreement.pdf
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.couchbase.lite;

import android.support.annotation.NonNull;


/**
 * <b>ENTERPRISE EDITION API</b><br><br>
 * <p>
 * PredictiveModel protocol that allows to integrate machine learning model into
 * CouchbaseLite Query via invoking the Function.prediction() function.
 */
public interface PredictiveModel {
    /**
     * The prediction callback called when invoking the Function.prediction() function
     * inside a query or an index. The input dictionary object's keys and values will be
     * corresponding to the 'input' dictionary parameter of theFunction.prediction() function.
     * <br>
     * If the prediction callback cannot return a result, the prediction callback
     * should return null value, which will be evaluated as MISSING.
     *
     * @param input The input dictionary.
     * @return The output dictionary.
     */
    Dictionary predict(@NonNull Dictionary input);
}
