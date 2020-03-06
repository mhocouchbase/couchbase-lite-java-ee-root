//
// Function.java
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

import java.util.Arrays;

import com.couchbase.lite.internal.utils.Preconditions;


/**
 * Query functions.
 */
public final class Function extends AbstractFunction {

    /**
     * <b>ENTERPRISE EDITION API</b><br><br>
     * <p>
     * Creates prediction function with the given model name and input. When running a query with
     * the prediction function, the corresponding predictive model registered to CouchbaseLite
     * Database class will be called with the given input to predict the result.
     * <p>
     * The prediction result returned by the predictive model will be in a form dictionary object.
     * To create an expression that refers to a property in the prediction result,
     * the propertyPath(path) method of the created PredictionFunction object
     * can be used.
     *
     * @param model The predictive model name registered to the CouchbaseLite Database.
     * @param input The expression evaluated to a dictionary.
     * @return A PredictionFunction object.
     */
    @NonNull
    public static PredictionFunction prediction(@NonNull String model, @NonNull Expression input) {
        return new PredictionFunction(model, input);
    }

    /**
     * <b>ENTERPRISE EDITION API</b><br><br>
     * <p>
     * Creates a function that returns the euclidean distance between the two input vectors.
     * The result is a non-negative floating-point number. The expression1 and expression2 must be
     * arrays of numbers, and must be the same length.
     *
     * @param expression1 The expression evaluated to an arrays of numbers.
     * @param expression2 The expression evaluated to an arrays of numbers.
     * @return The euclidean distance between two given input vectors.
     */
    @NonNull
    public static Expression euclideanDistance(@NonNull Expression expression1, @NonNull Expression expression2) {
        Preconditions.assertNotNull(expression1, "expression1");
        Preconditions.assertNotNull(expression2, "expression2");
        return new Expression.FunctionExpression("EUCLIDEAN_DISTANCE()", Arrays.asList(expression1, expression2));
    }

    /**
     * <b>ENTERPRISE EDITION API</b><br><br>
     * <p>
     * Creates a function that returns the squared euclidean distance between the two input vectors.
     * The result is a non-negative floating-point number. The expression1 and expression2 must be
     * arrays of numbers, and must be the same length.
     *
     * @param expression1 The expression evaluated to an arrays of numbers.
     * @param expression2 The expression evaluated to an arrays of numbers.
     * @return The squared euclidean distance between two given input vectors.
     */
    @NonNull
    public static Expression squaredEuclideanDistance(
        @NonNull Expression expression1,
        @NonNull Expression expression2) {
        Preconditions.assertNotNull(expression1, "expression1");
        Preconditions.assertNotNull(expression2, "expression2");
        return new Expression.FunctionExpression(
            "EUCLIDEAN_DISTANCE()",
            Arrays.asList(expression1, expression2, Expression.intValue(2)));
    }

    /**
     * <b>ENTERPRISE EDITION API</b><br><br>
     * <p>
     * Creates a function that returns the cosine distance which one minus the cosine similarity
     * between the two input vectors. The result is a floating-point number ranges from −1.0 to 1.0.
     * The expression1 and expression2 must be arrays of numbers, and must be the same length.
     *
     * @param expression1 The expression evaluated to an arrays of numbers.
     * @param expression2 The expression evaluated to an arrays of numbers.
     * @return The cosine distance between two given input vectors.
     */
    @NonNull
    public static Expression cosineDistance(@NonNull Expression expression1, @NonNull Expression expression2) {
        Preconditions.assertNotNull(expression1, "expression1");
        Preconditions.assertNotNull(expression2, "expression2");
        return new Expression.FunctionExpression("COSINE_DISTANCE()", Arrays.asList(expression1, expression2));
    }
}
