//
// Copyright (c) 2020, 2019 Couchbase, Inc.  All rights reserved.
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.couchbase.lite.internal.utils.DateUtils;
import com.couchbase.lite.internal.utils.Report;
import com.couchbase.lite.internal.utils.TestUtils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


public class PredictiveQueryTest extends BaseQueryTest {
    private static abstract class TestPredictiveModel implements PredictiveModel {
        private boolean allowCalls = true;
        private int numberOfCalls;

        @Override
        public Dictionary predict(@NonNull Dictionary input) {
            if (!allowCalls) { throw new IllegalStateException("Should not be called."); }

            numberOfCalls++;

            return getPrediction(input);
        }

        protected abstract String getName();

        protected abstract Dictionary getPrediction(Dictionary input);

        public void registerModel() { Database.prediction.registerModel(getName(), this); }

        public void unregisterModel() { Database.prediction.unregisterModel(getName()); }

        public void setAllowCalls(boolean allowCalls) { this.allowCalls = allowCalls; }

        public int getNumberOfCalls() { return numberOfCalls; }

        public void reset() {
            numberOfCalls = 0;
            allowCalls = true;
        }
    }

    private static final class EchoModel extends TestPredictiveModel {
        public static String NAME = "EchoModel";

        @Override
        public String getName() { return NAME; }

        @Override
        public Dictionary getPrediction(Dictionary input) { return input; }
    }

    private static final class AggregateModel extends TestPredictiveModel {
        public static String NAME = "AggregateModel";

        @Override
        public String getName() { return NAME; }

        @Override
        public Dictionary getPrediction(Dictionary input) {
            Array numbers = input.getArray("numbers");
            if (numbers == null) { return null; }

            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            int sum = 0;
            for (Object o: numbers) {
                int n = ((Number) o).intValue();
                min = Math.min(min, n);
                max = Math.max(max, n);
                sum = sum + n;
            }
            double avg = numbers.count() > 0 ? ((double) sum) / numbers.count() : 0.0;

            MutableDictionary output = new MutableDictionary();
            output.setInt("min", min);
            output.setInt("max", max);
            output.setInt("sum", sum);
            output.setDouble("avg", avg);
            return output;
        }

        public static Expression createInput(String propertyName) {
            Map<String, Object> input = new HashMap<>();
            input.put("numbers", Expression.property(propertyName));
            return Expression.map(input);
        }
    }

    private static final class TextModel extends TestPredictiveModel {
        public static String NAME = "TextModel";

        @Override
        public String getName() { return NAME; }

        @Override
        public Dictionary getPrediction(Dictionary input) {
            Blob blob = input.getBlob("text");
            if (blob == null) { return null; }

            if (blob.getContent() == null || !blob.getContentType().equals("text/plain")) {
                Report.log(LogLevel.WARNING, "Invalid blob content type; not text/plain.");
                return null;
            }

            String text = new String(blob.getContent());
            int wc = text.split("\\s+").length;
            int sc = text.split("[!?.:]+").length;

            MutableDictionary output = new MutableDictionary();
            output.setInt("wc", wc);
            output.setInt("sc", sc);
            return output;
        }

        public static Expression createInput(String propertyName) {
            return createInput(Expression.property(propertyName));
        }

        public static Expression createInput(Expression expression) {
            Map<String, Object> input = new HashMap<>();
            input.put("text", expression);
            return Expression.map(input);
        }
    }

    @Before
    public void setUpPredictiveQueryTest() {
        Database.prediction.unregisterModel(AggregateModel.NAME);
        Database.prediction.unregisterModel(TextModel.NAME);
        Database.prediction.unregisterModel(EchoModel.NAME);
    }

    @Test
    public void testRegisterAndUnregisterModel() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);
        final Query q = QueryBuilder
            .select(SelectResult.expression(prediction))
            .from(DataSource.database(baseTestDb));

        // Query before registering the model:
        TestUtils.assertThrowsCBL("CouchbaseLite.SQLite", 1, q::execute);

        AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        int rows = verifyQuery(q, (n, result) -> {
            Dictionary pred = result.getDictionary(0);
            assertEquals(15, pred.getInt("sum"));
        });
        assertEquals(1, rows);

        aggregateModel.unregisterModel();

        // Query after unregistering the model:
        TestUtils.assertThrowsCBL("CouchbaseLite.SQLite", 1, q::execute);
    }

    @Test
    public void testRegisterMultipleModelsWithSameName() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});

        String model = "TheModel";
        AggregateModel aggregateModel = new AggregateModel();
        Database.prediction.registerModel(model, aggregateModel);

        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);
        final Query q = QueryBuilder
            .select(SelectResult.expression(prediction))
            .from(DataSource.database(baseTestDb));
        int rows = verifyQuery(q, (n, result) -> {
            Dictionary pred = result.getDictionary(0);
            assertEquals(15, pred.getInt("sum"));
        });
        assertEquals(1, rows);

        // Register a new model with the same name:
        EchoModel echoModel = new EchoModel();
        Database.prediction.registerModel(model, echoModel);

        rows = verifyQuery(q, (n, result) -> {
            Dictionary pred = result.getDictionary(0);
            assertNull(pred.getValue("sum"));
            assertNotNull(pred.getArray("numbers"));
        });
        assertEquals(1, rows);

        Database.prediction.unregisterModel(model);
    }

    @Test
    public void testPredictionInputOutput() throws Exception {
        // Register echo model:
        EchoModel echoModel = new EchoModel();
        echoModel.registerModel();

        // Create a doc:
        MutableDocument doc = new MutableDocument();
        doc.setString("name", "Daniel");
        doc.setInt("number", 2);
        baseTestDb.save(doc);

        // Create a prediction function input:
        Date date = new Date();
        final String dateStr = DateUtils.toJson(date);
        Expression power = Function.power(Expression.property("number"), Expression.intValue(2));
        final Map<String, Object> map = new HashMap<>();
        map.put("null", null);
        // Literal:
        map.put("number1", 10);
        map.put("number2", 10.1);
        map.put("boolean", true);
        map.put("string", "hello");
        map.put("date", date);
        final Map<String, Object> subMap = new HashMap<>();
        subMap.put("foo", "bar");
        map.put("dict", subMap);
        final List<String> subList = Arrays.asList("1", "2", "3");
        map.put("array", subList);
        // Expression:
        map.put("expr_property", Expression.property("name"));
        map.put("expr_value_number1", Expression.value(20));
        map.put("expr_value_number2", Expression.value(20.1));
        map.put("expr_value_boolean", Expression.value(true));
        map.put("expr_value_string", Expression.value("hi"));
        map.put("expr_value_date", Expression.value(date));
        map.put("expr_value_null", Expression.value(null));
        final Map<String, Object> subExprMap = new HashMap<>();
        subExprMap.put("ping", "pong");
        map.put("expr_value_dict", Expression.value(subExprMap));
        final List<String> subExprList = Arrays.asList("4", "5", "6");
        map.put("expr_value_array", Expression.value(subExprList));
        map.put("expr_power", power);

        Expression input = Expression.value(map);
        String model = EchoModel.NAME;
        PredictionFunction prediction = Function.prediction(model, input);

        final Query q = QueryBuilder
            .select(SelectResult.expression(prediction))
            .from(DataSource.database(baseTestDb));

        int rows = verifyQuery(q, (n, result) -> {
            Dictionary pred = result.getDictionary(0);
            assertEquals(map.size(), pred.count());
            // Literal:
            assertEquals(10, pred.getInt("number1"));
            assertEquals(10.1, pred.getDouble("number2"), 0.0);
            assertTrue(pred.getBoolean("boolean"));
            assertEquals("hello", pred.getString("string"));
            assertEquals(dateStr, DateUtils.toJson(pred.getDate("date")));
            assertNull(pred.getString("null"));
            assertEquals(subMap, pred.getDictionary("dict").toMap());
            assertArrayEquals(new String[] {"1", "2", "3"}, pred.getArray("array").toList().toArray(new String[3]));
            // Expression:
            assertEquals("Daniel", pred.getString("expr_property"));
            assertEquals(20, pred.getInt("expr_value_number1"));
            assertEquals(20.1, pred.getDouble("expr_value_number2"), 0.0);
            assertTrue(pred.getBoolean("expr_value_boolean"));
            assertEquals("hi", pred.getString("expr_value_string"));
            assertEquals(dateStr, DateUtils.toJson(pred.getDate("expr_value_date")));
            assertNull(pred.getString("expr_value_null"));
            assertEquals(subExprMap, pred.getDictionary("expr_value_dict").toMap());
            assertArrayEquals(
                subExprList.toArray(new String[3]),
                pred.getArray("expr_value_array").toList().toArray(new String[3]));
            assertEquals(4, pred.getInt("expr_power"));
        });

        assertEquals(1, rows);

        Database.prediction.unregisterModel(model);
    }

    @Test
    public void testPredictionWithBlobPropertyInput() throws Exception {
        final String[] texts = new String[] {
            BLOB_CONTENT,
            "Clocks on fox tick. Clocks on Knox tock. Six sick bricks tick. Six sick chicks tock."
        };

        for (String text: texts) {
            MutableDocument doc = new MutableDocument();
            doc.setBlob("text", new Blob("text/plain", text.getBytes(StandardCharsets.UTF_8)));
            baseTestDb.save(doc);
        }

        TextModel textModel = new TextModel();
        textModel.registerModel();

        String model = TextModel.NAME;
        Expression input = TextModel.createInput("text");
        PredictionFunction prediction = Function.prediction(model, input);

        Query q = QueryBuilder
            .select(
                SelectResult.property("text"),
                SelectResult.expression(prediction.propertyPath("wc")).as("wc"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("wc").greaterThan(Expression.value(15)));

        int rows = verifyQuery(q, (n, result) -> {
            Blob blob = result.getBlob("text");
            String text = new String(blob.getContent());
            assertEquals(texts[1], text);
            assertEquals(16, result.getInt("wc"));
        });
        assertEquals(1, rows);

        textModel.unregisterModel();
    }

    @Test
    public void testPredictionWithBlobParameterInput() throws Exception {
        baseTestDb.save(new MutableDocument());

        TextModel textModel = new TextModel();
        textModel.registerModel();

        String model = TextModel.NAME;
        Expression input = TextModel.createInput(Expression.parameter("text"));
        PredictionFunction prediction = Function.prediction(model, input);

        Query q = QueryBuilder
            .select(
                SelectResult.property("text"),
                SelectResult.expression(prediction.propertyPath("wc")).as("wc"))
            .from(DataSource.database(baseTestDb));

        Parameters params = new Parameters();
        params.setBlob("text", new Blob("text/plain", BLOB_CONTENT.getBytes(StandardCharsets.UTF_8)));
        q.setParameters(params);

        int rows = verifyQuery(q, (n, result) -> assertEquals(14, result.getInt("wc")));
        assertEquals(1, rows);

        textModel.unregisterModel();
    }

    @Test(expected = CouchbaseLiteException.class)
    public void testPredictionWithUnsupportedInputType1() throws CouchbaseLiteException {
        loadNumberedDocs(10);

        EchoModel echoModel = new EchoModel();
        echoModel.registerModel();
        try {
            QueryBuilder
                .select(SelectResult.expression(Function.prediction(EchoModel.NAME, Expression.value("string"))))
                .from(DataSource.database(baseTestDb))
                .execute();
        }
        finally { echoModel.unregisterModel(); }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPredictionWithUnsupportedInputType2() throws CouchbaseLiteException {
        loadNumberedDocs(10);

        Map<String, Object> map = new HashMap<>();
        map.put("key", this);

        EchoModel echoModel = new EchoModel();
        echoModel.registerModel();
        try {
            QueryBuilder
                .select(SelectResult.expression(Function.prediction(EchoModel.NAME, Expression.map(map))))
                .from(DataSource.database(baseTestDb))
                .execute();
        }
        finally { echoModel.unregisterModel(); }
    }

    @Test
    public void testQueryPredictionResultDictionary() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});
        createDocument(new int[] {6, 7, 8, 9, 10});

        final AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        final Query q = QueryBuilder
            .select(SelectResult.property("numbers"), SelectResult.expression(prediction))
            .from(DataSource.database(baseTestDb));

        int rows = verifyQuery(q, (n, result) -> {
            Array numbers = result.getArray(0);
            MutableDictionary dict = new MutableDictionary();
            dict.setArray("numbers", numbers);
            Dictionary expected = aggregateModel.predict(dict);
            Dictionary prediction1 = result.getDictionary(1);
            assertEquals(expected.getInt("sum"), prediction1.getInt("sum"));
            assertEquals(expected.getInt("min"), prediction1.getInt("min"));
            assertEquals(expected.getInt("max"), prediction1.getInt("max"));
            assertEquals(expected.getDouble("avg"), prediction1.getDouble("avg"), 0.0);
        });
        assertEquals(2, rows);

        aggregateModel.unregisterModel();
    }

    @Test
    public void testQueryPredictionValues() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});
        createDocument(new int[] {6, 7, 8, 9, 10});

        final AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        final Query q = QueryBuilder
            .select(
                SelectResult.property("numbers"),
                SelectResult.expression(prediction.propertyPath("sum")).as("sum"),
                SelectResult.expression(prediction.propertyPath("min")).as("min"),
                SelectResult.expression(prediction.propertyPath("max")).as("max"),
                SelectResult.expression(prediction.propertyPath("avg")).as("avg"))
            .from(DataSource.database(baseTestDb));

        int rows = verifyQuery(q, (n, result) -> {
            Array numbers = result.getArray(0);
            MutableDictionary dict = new MutableDictionary();
            dict.setArray("numbers", numbers);
            Dictionary expected = aggregateModel.predict(dict);

            int sum = result.getInt(1);
            int min = result.getInt(2);
            int max = result.getInt(3);
            double avg = result.getDouble(4);

            assertEquals(sum, result.getInt("sum"));
            assertEquals(min, result.getInt("min"));
            assertEquals(max, result.getInt("max"));
            assertEquals(avg, result.getDouble("avg"), 0.0);

            assertEquals(expected.getInt("sum"), sum);
            assertEquals(expected.getInt("min"), min);
            assertEquals(expected.getInt("max"), max);
            assertEquals(expected.getDouble("avg"), avg, 0.0);
        });
        assertEquals(2, rows);

        aggregateModel.unregisterModel();
    }

    @Test
    public void testWhereUsingPredictionValues() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});
        createDocument(new int[] {6, 7, 8, 9, 10});

        final AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        final Query q = QueryBuilder
            .select(
                SelectResult.property("numbers"),
                SelectResult.expression(prediction.propertyPath("sum")).as("sum"),
                SelectResult.expression(prediction.propertyPath("min")).as("min"),
                SelectResult.expression(prediction.propertyPath("max")).as("max"),
                SelectResult.expression(prediction.propertyPath("avg")).as("avg"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").equalTo(Expression.value(15)));

        int rows = verifyQuery(q, (n, result) -> {
            int sum = result.getInt(1);
            int min = result.getInt(2);
            int max = result.getInt(3);
            double avg = result.getInt(4);

            assertEquals(15, sum);
            assertEquals(1, min);
            assertEquals(5, max);
            assertEquals(3.0, avg, 0.0);
        });
        assertEquals(1, rows);

        aggregateModel.unregisterModel();
    }

    @Test
    public void testOrderByUsingPredictionValues() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});
        createDocument(new int[] {6, 7, 8, 9, 10});

        final AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        final Query q = QueryBuilder
            .select(SelectResult.expression(prediction.propertyPath("sum")).as("sum"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").greaterThan(Expression.value(1)))
            .orderBy(Ordering.expression(prediction.propertyPath("sum")).descending());

        int rows = verifyQuery(q, (n, result) -> {
            int sum = result.getInt(0);
            assertEquals((n == 1 ? 40 : 15), sum);

        });
        assertEquals(2, rows);

        aggregateModel.unregisterModel();
    }

    @Test
    public void testPredictiveModelReturningNull() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});

        MutableDocument doc = new MutableDocument();
        doc.setString("text", BLOB_CONTENT);
        baseTestDb.save(doc);

        AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        final Query q = QueryBuilder
            .select(
                SelectResult.expression(prediction),
                SelectResult.expression(prediction.propertyPath("sum")))
            .from(DataSource.database(baseTestDb));

        int rows = verifyQuery(q, (n, result) -> {
            if (n == 1) {
                assertNotNull(result.getDictionary(0));
                assertEquals(15, result.getInt(1));
            }
            else {
                assertNull(result.getValue(0));
                assertNull(result.getValue(0));
            }
        });
        assertEquals(2, rows);

        // Evaluate with nullOrMissing:

        Query q2 = QueryBuilder
            .select(
                SelectResult.expression(prediction),
                SelectResult.expression(prediction.propertyPath("sum")))
            .from(DataSource.database(baseTestDb))
            .where(prediction.notNullOrMissing());

        rows = verifyQuery(q2, (n, result) -> {
            assertNotNull(result.getDictionary(0));
            assertEquals(15, result.getInt(1));
        });
        assertEquals(1, rows);

        aggregateModel.unregisterModel();
    }

    @Test
    public void testIndexPredictionValueUsingValueIndex() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});
        createDocument(new int[] {6, 7, 8, 9, 10});

        final AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        Index index = IndexBuilder.valueIndex(ValueIndexItem.expression(
            prediction.propertyPath("sum")));
        baseTestDb.createIndex("SumIndex", index);

        final Query q = QueryBuilder
            .select(SelectResult.expression(prediction.propertyPath("sum")).as("sum"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").equalTo(Expression.value(15)));

        String explain = q.explain();
        assertTrue(explain.indexOf("USING INDEX SumIndex") > 0);

        int rows = verifyQuery(q, (n, result) -> assertEquals(15, result.getInt(0)));
        assertEquals(1, rows);
        assertEquals(2, aggregateModel.getNumberOfCalls());

        aggregateModel.reset();
    }

    @Test
    public void testIndexMultiplePredictionValuesUsingValueIndex() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});
        createDocument(new int[] {6, 7, 8, 9, 10});

        final AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        Index sumIndex = IndexBuilder.valueIndex(ValueIndexItem.expression(prediction.propertyPath("sum")));
        baseTestDb.createIndex("SumIndex", sumIndex);

        Index avgIndex = IndexBuilder.valueIndex(ValueIndexItem.expression(prediction.propertyPath("avg")));
        baseTestDb.createIndex("AvgIndex", avgIndex);

        final Query q = QueryBuilder
            .select(
                SelectResult.expression(prediction.propertyPath("sum")).as("sum"),
                SelectResult.expression(prediction.propertyPath("avg")).as("avg"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").lessThanOrEqualTo(Expression.value(15))
                .or(prediction.propertyPath("avg").equalTo(Expression.value(8))));

        String explain = q.explain();
        assertTrue(explain.indexOf("USING INDEX SumIndex") > 0);
        assertTrue(explain.indexOf("USING INDEX AvgIndex") > 0);

        int rows = verifyQuery(q, (n, result) -> {
            assertTrue((result.getInt(0) == 15 || result.getInt(1) == 8));
        });
        assertEquals(2, rows);
    }

    @Test
    public void testIndexCompoundPredictiveValuesUsingValueIndex() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});
        createDocument(new int[] {6, 7, 8, 9, 10});

        final AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        Index index = IndexBuilder.valueIndex(
            ValueIndexItem.expression(prediction.propertyPath("sum")),
            ValueIndexItem.expression(prediction.propertyPath("avg")));
        baseTestDb.createIndex("SumAvgIndex", index);

        aggregateModel.setAllowCalls(false);

        final Query q = QueryBuilder
            .select(
                SelectResult.expression(prediction.propertyPath("sum")).as("sum"),
                SelectResult.expression(prediction.propertyPath("avg")).as("avg"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").equalTo(Expression.value(15)).and(
                prediction.propertyPath("avg").equalTo(Expression.value(3))));

        String explain = q.explain();
        assertTrue(explain.indexOf("USING INDEX SumAvgIndex") > 0);

        int rows = verifyQuery(q, (n, result) -> {
            assertEquals(15, result.getInt(0));
            assertEquals(3, result.getInt(1));
        });
        assertEquals(1, rows);
        assertEquals(4, aggregateModel.getNumberOfCalls());

        aggregateModel.reset();
    }

    @Test
    public void testIndexPredictionResultUsingPredictiveIndex() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});
        createDocument(new int[] {6, 7, 8, 9, 10});

        final AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        Index index = IndexBuilder.predictiveIndex(model, input, null);
        baseTestDb.createIndex("AggIndex", index);

        aggregateModel.setAllowCalls(false);

        final Query q = QueryBuilder
            .select(
                SelectResult.property("numbers"),
                SelectResult.expression(prediction.propertyPath("sum")).as("sum"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").equalTo(Expression.value(15)));

        String explain = q.explain();
        // Check that the AggIndex is not used. The AggIndex is not used because the predictive index
        // is created without the properties specified so that only cache table is used and there will
        // be no actual SQLite index created.
        assertFalse(explain.contains("USING INDEX AggIndex"));

        int rows = verifyQuery(q, (n, result) -> assertEquals(15, result.getInt(1)));
        assertEquals(1, rows);
        assertEquals(2, aggregateModel.getNumberOfCalls());

        aggregateModel.reset();
        aggregateModel.unregisterModel();
    }

    @Test
    public void testIndexPredictionValueUsingPredictiveIndex() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});
        createDocument(new int[] {6, 7, 8, 9, 10});

        final AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        Index index = IndexBuilder.predictiveIndex(model, input, Arrays.asList("sum"));
        baseTestDb.createIndex("SumIndex", index);

        aggregateModel.setAllowCalls(false);

        final Query q = QueryBuilder
            .select(
                SelectResult.property("numbers"),
                SelectResult.expression(prediction.propertyPath("sum")).as("sum"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").equalTo(Expression.value(15)));

        String explain = q.explain();
        assertTrue(explain.indexOf("USING INDEX SumIndex") > 0);

        int rows = verifyQuery(q, (n, result) -> assertEquals(15, result.getInt(1)));
        assertEquals(1, rows);
        assertEquals(2, aggregateModel.getNumberOfCalls());

        aggregateModel.reset();
        aggregateModel.unregisterModel();
    }

    @Test
    public void testIndexMultiplePredictionValuesUsingPredictiveIndex() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});
        createDocument(new int[] {6, 7, 8, 9, 10});

        final AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        Index sumIndex = IndexBuilder.predictiveIndex(model, input, Arrays.asList("sum"));
        baseTestDb.createIndex("SumIndex", sumIndex);

        Index avgIndex = IndexBuilder.predictiveIndex(model, input, Arrays.asList("avg"));
        baseTestDb.createIndex("AvgIndex", avgIndex);

        aggregateModel.setAllowCalls(false);

        final Query q = QueryBuilder
            .select(
                SelectResult.expression(prediction.propertyPath("sum")).as("sum"),
                SelectResult.expression(prediction.propertyPath("avg")).as("avg"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").lessThanOrEqualTo(Expression.value(15)).or(
                prediction.propertyPath("avg").equalTo(Expression.value(8))));

        String explain = q.explain();
        assertTrue(explain.indexOf("USING INDEX SumIndex") > 0);
        assertTrue(explain.indexOf("USING INDEX AvgIndex") > 0);

        int rows = verifyQuery(q, (n, result) -> {
            assertTrue((result.getInt(0) == 15 || result.getInt(1) == 8));
        });
        assertEquals(2, rows);
        assertEquals(2, aggregateModel.getNumberOfCalls());

        aggregateModel.reset();
        aggregateModel.unregisterModel();
    }

    @Test
    public void testIndexCompoundPredictiveValuesUsingPredictiveIndex() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});
        createDocument(new int[] {6, 7, 8, 9, 10});

        final AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        Index index = IndexBuilder.predictiveIndex(model, input, Arrays.asList("sum", "avg"));
        baseTestDb.createIndex("SumAvgIndex", index);

        aggregateModel.setAllowCalls(false);

        final Query q = QueryBuilder
            .select(
                SelectResult.expression(prediction.propertyPath("sum")).as("sum"),
                SelectResult.expression(prediction.propertyPath("avg")).as("avg"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").equalTo(Expression.value(15)).and(
                prediction.propertyPath("avg").equalTo(Expression.value(3))));

        String explain = q.explain();
        assertTrue(explain.indexOf("USING INDEX SumAvgIndex") > 0);

        int rows = verifyQuery(q, (n, result) -> {
            assertEquals(15, result.getInt(0));
            assertEquals(3, result.getInt(1));

        });
        assertEquals(1, rows);
        assertEquals(2, aggregateModel.getNumberOfCalls());

        aggregateModel.reset();
        aggregateModel.unregisterModel();
    }

    @Test
    public void testDeletePredictiveIndex() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});
        createDocument(new int[] {6, 7, 8, 9, 10});

        final AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        // Index:
        Index index = IndexBuilder.predictiveIndex(model, input, Arrays.asList("sum"));
        baseTestDb.createIndex("SumIndex", index);

        aggregateModel.setAllowCalls(false);

        // Query with index:
        final Query q = QueryBuilder
            .select(SelectResult.property("numbers"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").equalTo(Expression.value(15)));

        String explain = q.explain();
        assertTrue(explain.indexOf("USING INDEX SumIndex") > 0);

        int rows = verifyQuery(q, (n, result) -> {
            Array numbers = result.getArray(0);
            assertNotNull(numbers);
            assertTrue(numbers.count() > 0);
        });
        assertEquals(1, rows);
        assertEquals(2, aggregateModel.getNumberOfCalls());

        // Delete SumIndex:
        baseTestDb.deleteIndex("SumIndex");

        // Query again:
        aggregateModel.reset();
        final Query q2 = QueryBuilder
            .select(SelectResult.property("numbers"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").equalTo(Expression.value(15)));

        explain = q2.explain();
        // Check that the SumIndex is not used:
        assertFalse(explain.contains("USING INDEX SumIndex"));

        rows = verifyQuery(q2, (n, result) -> {
            Array numbers = result.getArray(0);
            assertNotNull(numbers);
            assertTrue(numbers.count() > 0);
        });
        assertEquals(1, rows);
        assertEquals(4, aggregateModel.getNumberOfCalls()); // Note: verifyQuery executes query twice

        aggregateModel.reset();
        aggregateModel.unregisterModel();
    }

    @Test
    public void testDeletePredictiveIndexesSharingSameCacheTable() throws Exception {
        createDocument(new int[] {1, 2, 3, 4, 5});
        createDocument(new int[] {6, 7, 8, 9, 10});

        final AggregateModel aggregateModel = new AggregateModel();
        aggregateModel.registerModel();

        String model = AggregateModel.NAME;
        Expression input = AggregateModel.createInput("numbers");
        PredictionFunction prediction = Function.prediction(model, input);

        // Create agg index:
        Index aggIndex = IndexBuilder.predictiveIndex(model, input, null);
        baseTestDb.createIndex("AggIndex", aggIndex);

        // Create sum index:
        Index sumIndex = IndexBuilder.predictiveIndex(model, input, Arrays.asList("sum"));
        baseTestDb.createIndex("SumIndex", sumIndex);

        // Create avg index:
        Index avgIndex = IndexBuilder.predictiveIndex(model, input, Arrays.asList("avg"));
        baseTestDb.createIndex("AvgIndex", avgIndex);

        // Query:
        final Query q = QueryBuilder
            .select(SelectResult.property("numbers"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").lessThanOrEqualTo(Expression.value(15)).or(
                prediction.propertyPath("avg").equalTo(Expression.value(8))));

        String explain = q.explain();
        assertTrue(explain.indexOf("USING INDEX SumIndex") > 0);
        assertTrue(explain.indexOf("USING INDEX AvgIndex") > 0);

        int rows = verifyQuery(q, (n, result) -> {
            Array numbers = result.getArray(0);
            assertNotNull(numbers);
            assertTrue(numbers.count() > 0);
        });
        assertEquals(2, rows);
        assertEquals(2, aggregateModel.getNumberOfCalls());

        // Delete SumIndex:
        baseTestDb.deleteIndex("SumIndex");

        // Note: when having only one index, SQLite optimizer doesn't utilize the index
        //       when using OR expr. Hence explicitly test each index with two queries:
        aggregateModel.reset();
        aggregateModel.setAllowCalls(false);

        final Query q2 = QueryBuilder
            .select(SelectResult.property("numbers"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").equalTo(Expression.value(15)));

        explain = q2.explain();
        // Check that the SumIndex is not used:
        assertFalse(explain.contains("USING INDEX SumIndex"));

        rows = verifyQuery(q2, (n, result) -> {
            Array numbers = result.getArray(0);
            assertNotNull(numbers);
            assertTrue(numbers.count() > 0);
        });

        assertEquals(1, rows);
        assertEquals(0, aggregateModel.getNumberOfCalls());

        aggregateModel.reset();
        aggregateModel.setAllowCalls(false);

        final Query q3 = QueryBuilder
            .select(SelectResult.property("numbers"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("avg").equalTo(Expression.value(8)));

        explain = q3.explain();
        assertTrue(explain.indexOf("USING INDEX AvgIndex") > 0);

        rows = verifyQuery(q3, (n, result) -> {
            Array numbers = result.getArray(0);
            assertNotNull(numbers);
            assertTrue(numbers.count() > 0);
        });

        assertEquals(1, rows);
        assertEquals(0, aggregateModel.getNumberOfCalls());

        // Delete AvgIndex
        baseTestDb.deleteIndex("AvgIndex");

        aggregateModel.reset();
        aggregateModel.setAllowCalls(false);

        final Query q4 = QueryBuilder
            .select(SelectResult.property("numbers"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("avg").equalTo(Expression.value(8)));

        explain = q4.explain();
        // Check that the SumIndex and AvgIndex are not used:
        assertFalse(explain.contains("USING INDEX SumIndex"));
        assertFalse(explain.contains("USING INDEX AvgIndex"));

        rows = verifyQuery(q4, (n, result) -> {
            Array numbers = result.getArray(0);
            assertNotNull(numbers);
            assertTrue(numbers.count() > 0);
        });

        assertEquals(1, rows);
        assertEquals(0, aggregateModel.getNumberOfCalls());

        // Delete AggIndex
        baseTestDb.deleteIndex("AggIndex");

        aggregateModel.reset();

        final Query q5 = QueryBuilder
            .select(SelectResult.property("numbers"))
            .from(DataSource.database(baseTestDb))
            .where(prediction.propertyPath("sum").lessThanOrEqualTo(Expression.value(15)).or(
                prediction.propertyPath("avg").equalTo(Expression.value(8))));

        explain = q5.explain();
        // Check that the SumIndex and AvgIndex are not used:
        assertFalse(explain.contains("USING INDEX SumIndex"));
        assertFalse(explain.contains("USING INDEX AvgIndex"));

        rows = verifyQuery(q5, (n, result) -> {
            Array numbers = result.getArray(0);
            assertNotNull(numbers);
            assertTrue(numbers.count() > 0);
        });

        assertEquals(2, rows);
        assertTrue(aggregateModel.getNumberOfCalls() > 0);
    }

    @Test
    public void testEuclideanDistance() throws Exception {
        Object[][] tests = {
            {Arrays.asList(10, 10), Arrays.asList(13, 14), 5},
            {Arrays.asList(1, 2, 3), Arrays.asList(1, 2, 3), 0},
            {Arrays.asList(), Arrays.asList(), 0},
            {Arrays.asList(1, 2), Arrays.asList(1, 2, 3), null},
            {Arrays.asList(1, 2), "foo", null},
        };

        for (Object[] test: tests) {
            MutableDocument doc = new MutableDocument();
            doc.setValue("v1", test[0]);
            doc.setValue("v2", test[1]);
            doc.setValue("distance", test[2]);
            baseTestDb.save(doc);
        }

        Expression distance = Function.euclideanDistance(
            Expression.property("v1"),
            Expression.property("v2"));

        final Query q = QueryBuilder
            .select(
                SelectResult.expression(distance),
                SelectResult.property("distance"))
            .from(DataSource.database(baseTestDb));

        int rows = verifyQuery(q, (n, result) -> {
            if (result.getValue(1) == null) { assertNull(result.getValue(0)); }
            else { assertEquals(result.getInt(1), result.getInt(0)); }
        });

        assertEquals(tests.length, rows);
    }

    @Test
    public void testSquaredEuclideanDistance() throws Exception {
        Object[][] tests = {
            {Arrays.asList(10, 10), Arrays.asList(13, 14), 25},
            {Arrays.asList(1, 2, 3), Arrays.asList(1, 2, 3), 0},
            {Arrays.asList(), Arrays.asList(), 0},
            {Arrays.asList(1, 2), Arrays.asList(1, 2, 3), null},
            {Arrays.asList(1, 2), "foo", null},
        };

        for (Object[] test: tests) {
            MutableDocument doc = new MutableDocument();
            doc.setValue("v1", test[0]);
            doc.setValue("v2", test[1]);
            doc.setValue("distance", test[2]);
            baseTestDb.save(doc);
        }

        Expression distance = Function.squaredEuclideanDistance(
            Expression.property("v1"),
            Expression.property("v2"));

        final Query q = QueryBuilder
            .select(
                SelectResult.expression(distance),
                SelectResult.property("distance"))
            .from(DataSource.database(baseTestDb));

        int rows = verifyQuery(q, (n, result) -> {
            if (result.getValue(1) == null) { assertNull(result.getValue(0)); }
            else { assertEquals(result.getInt(1), result.getInt(0)); }
        });

        assertEquals(tests.length, rows);
    }

    @Test
    public void testCosineDistance() throws Exception {
        Object[][] tests = {
            {Arrays.asList(10, 0), Arrays.asList(0, 99), 1.0},
            {Arrays.asList(1, 2, 3), Arrays.asList(1, 2, 3), 0},
            {Arrays.asList(1, 0, -1), Arrays.asList(-1, -1, 0), 1.5},
            {Arrays.asList(), Arrays.asList(), null},
            {Arrays.asList(1, 2), Arrays.asList(1, 2, 3), null},
            {Arrays.asList(1, 2), "foo", null},
        };

        for (Object[] test: tests) {
            MutableDocument doc = new MutableDocument();
            doc.setValue("v1", test[0]);
            doc.setValue("v2", test[1]);
            doc.setValue("distance", test[2]);
            baseTestDb.save(doc);
        }

        Expression distance = Function.cosineDistance(
            Expression.property("v1"),
            Expression.property("v2"));

        final Query q = QueryBuilder
            .select(
                SelectResult.expression(distance),
                SelectResult.property("distance"))
            .from(DataSource.database(baseTestDb));

        int rows = verifyQuery(q, (n, result) -> {
            if (result.getValue(1) == null) { assertNull(result.getValue(0)); }
            else { assertEquals(result.getDouble(1), result.getDouble(0), 0.0); }
        });

        assertEquals(tests.length, rows);
    }

    private MutableDocument createDocument(int[] numbers) throws CouchbaseLiteException {
        MutableDocument doc = new MutableDocument();
        List<Object> list = new ArrayList<>();
        for (int n: numbers) {
            list.add(n);
        }
        doc.setArray("numbers", new MutableArray(list));
        baseTestDb.save(doc);
        return doc;
    }
}
