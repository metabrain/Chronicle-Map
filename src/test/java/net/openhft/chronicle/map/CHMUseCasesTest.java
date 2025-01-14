/*
 *      Copyright (C) 2015  higherfrequencytrading.com
 *
 *      This program is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU Lesser General Public License as published by
 *      the Free Software Foundation, either version 3 of the License.
 *
 *      This program is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU Lesser General Public License for more details.
 *
 *      You should have received a copy of the GNU Lesser General Public License
 *      along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.map;

import com.google.common.primitives.Chars;
import net.openhft.chronicle.hash.function.SerializableFunction;
import net.openhft.chronicle.hash.replication.SingleChronicleHashReplication;
import net.openhft.chronicle.hash.replication.TcpTransportAndNetworkConfig;
import net.openhft.chronicle.map.fromdocs.BondVOInterface;
import net.openhft.lang.io.ByteBufferBytes;
import net.openhft.lang.io.Bytes;
import net.openhft.lang.io.serialization.BytesMarshallable;
import net.openhft.lang.io.serialization.impl.*;
import net.openhft.lang.model.constraints.MaxSize;
import net.openhft.lang.model.constraints.NotNull;
import net.openhft.lang.values.*;
import org.junit.After;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static java.util.Arrays.asList;
import static net.openhft.chronicle.map.fromdocs.OpenJDKAndHashMapExamplesTest.parseYYYYMMDD;
import static org.junit.Assert.*;

/**
 * This test enumerates common use cases for keys and values.
 */
@RunWith(value = Parameterized.class)
public class CHMUseCasesTest {


    enum TypeOfMap {SIMPLE, SIMPLE_PERSISTED, REPLICATED}

    private final TypeOfMap typeOfMap;

    Collection<Closeable> closeables = new ArrayList<Closeable>();

    public CHMUseCasesTest(TypeOfMap typeOfMap) {
        this.typeOfMap = typeOfMap;
    }

    @After
    public void after() {
        for (Closeable c : closeables) {

            try {
                c.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        closeables.clear();
        map2 = null;
        map1 = null;
    }

    ChronicleMap map1;
    ChronicleMap map2;

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return asList(new Object[][]{
                {
                        TypeOfMap.SIMPLE
                },

                {
                        TypeOfMap.REPLICATED
                },

                {
                        TypeOfMap.SIMPLE_PERSISTED
                }
        });

    }


    /**
     * * waits until map1 and map2 show the same value
     *
     * @param timeOutMs timeout in milliseconds
     */
    private void waitTillEqual(final int timeOutMs) {
        int t = 0;
        for (; t < timeOutMs; t++) {
            if (map1.equals(map2))
                break;
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }


    private void mapChecks() {
        if (typeOfMap == TypeOfMap.REPLICATED) {

            // see HCOLL-265 Chronicle Maps with Identical char[] values are not equal1
            if (map1.valueClass() == char[].class ||
                    map1.valueClass() == byte[].class ||
                    map1.valueClass() == byte[][].class) {

                waitTillEqual(5000);

                assertArrayValueEquals(map1, map2);
                if (typeOfMap == TypeOfMap.SIMPLE)
                    checkJsonSerilization();

                return;
            }


            // see HCOLL-265 Chronicle Maps with Identical char[] values are not equal1
            if (map1.keyClass() == char[].class ||
                    map1.keyClass() == byte[][].class) {

                return;
            }


            if (ByteBuffer.class.isAssignableFrom(map1.valueClass()) ||
                    ByteBuffer.class.isAssignableFrom(map1.keyClass()))
                return;


            waitTillEqual(5000);
            assertEquals(map1, map2);
        }

        if (typeOfMap == TypeOfMap.SIMPLE)
            checkJsonSerilization();

    }

    private void assertArrayValueEquals(ChronicleMap map1, ChronicleMap map2) {

        assertEquals(map1.size(), map2.size());


        for (Object key : map1.keySet()) {

            if (map1.valueClass() == byte[].class)
                Assert.assertArrayEquals((byte[]) map1.get(key), (byte[]) map2.get(key));

            else if (map1.valueClass() == char[].class)
                Assert.assertArrayEquals((char[]) map1.get(key), (char[]) map2.get(key));
            else if (map1.valueClass() == byte[][].class) {
                byte[][] o1 = (byte[][]) map1.get(key);
                byte[][] o2 = (byte[][]) map2.get(key);


                Assert.assertEquals(o1.length, o2.length);
                for (int i = 0; i < o1.length; i++) {
                    Assert.assertArrayEquals(o1[i], o2[i]);
                }

            } else throw new IllegalStateException("unsupported type");

        }
    }


    private void checkJsonSerilization() {
        File file = null;
        try {
            file = File.createTempFile("chronicle-map-", ".json");
        } catch (IOException e) {
            e.printStackTrace();
        }

        file.deleteOnExit();
        try {

            map1.getAll(file);

            VanillaChronicleMap vanillaMap = (VanillaChronicleMap) map1;
            ChronicleMapBuilder builder = ChronicleMapBuilder.of(map1.keyClass(),
                    map1.valueClass())
                    .entriesPerSegment(vanillaMap.entriesPerSegment)
                    .actualSegments(vanillaMap.actualSegments)
                    .actualChunksPerSegment(vanillaMap.actualChunksPerSegment);
            if (!vanillaMap.constantlySizedEntry)
                builder.actualChunkSize((int) vanillaMap.chunkSize);
            try (ChronicleMap<Integer, Double> actual = builder.create()) {
                actual.putAll(file);


                if (map1.valueClass() == char[].class ||
                        map1.valueClass() == byte[].class ||
                        map1.valueClass() == byte[][].class) {
                    assertArrayValueEquals(map1, actual);
                } else {
                    Assert.assertEquals(map1, actual);
                }
            }

        } catch (IOException e) {
            Assert.fail();
        } finally {
            file.delete();
        }
    }

    static volatile int i = 0;

    private <X, Y> ChronicleMap<X, Y> newInstance(ChronicleMapBuilder<X, Y> builder) throws
            IOException {
        switch (typeOfMap) {

            case SIMPLE:
                map2 = null;
                map1 = builder.create();
                closeables.add(map1);
                return map1;

            case SIMPLE_PERSISTED:
                File file0 = null;
                try {
                    file0 = File.createTempFile("chronicle-map-", ".map");
                } catch (IOException e) {
                    e.printStackTrace();
                }

                file0.deleteOnExit();
                final File file = file0;
                map1 = builder.createPersistedTo(file);
                closeables.add(map1);
                closeables.add(new Closeable() {
                    @Override
                    public void close() throws IOException {
                        file.delete();
                    }
                });

                return map1;

            case REPLICATED: {

                map2 = null;
                {
                    final TcpTransportAndNetworkConfig tcpConfig1 = TcpTransportAndNetworkConfig
                            .of(8086)
                            .heartBeatInterval(1, TimeUnit.SECONDS)
                            .tcpBufferSize(1024 * 64);


                    map2 = builder
                            .replication(SingleChronicleHashReplication.builder()
                                    .tcpTransportAndNetwork(tcpConfig1).name("server")
                                    .createWithId((byte) 1))
                            .instance()
                            .name("server")
                            .create();
                    closeables.add(map2);

                }
                {
                    final TcpTransportAndNetworkConfig tcpConfig2 = TcpTransportAndNetworkConfig.of
                            (8087, new InetSocketAddress("localhost", 8086))
                            .heartBeatInterval(1, TimeUnit.SECONDS)
                            .tcpBufferSize(1024 * 64);

                    map1 = builder
                            .replication(SingleChronicleHashReplication.builder()
                                    .tcpTransportAndNetwork(tcpConfig2).name("map2")
                                    .createWithId((byte) 2))
                            .instance()
                            .name("map2")
                            .create();
                    closeables.add(map1);
                    return map1;

                }


            }

            default:
                throw new IllegalStateException();
        }
    }

    static class PrefixStringFunction implements SerializableFunction<String, String> {
        private final String prefix;

        public PrefixStringFunction(@NotNull String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String apply(String s) {
            return prefix + s;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof PrefixStringFunction &&
                    prefix.equals(((PrefixStringFunction) obj).prefix);
        }

        @Override
        public String toString() {
            return prefix;
        }
    }



    interface I1 {
        String getStrAt( @MaxSize(10) int i);
        void setStrAt(@MaxSize(10) int i, @MaxSize(10) String str);
    }

    @Test
    public void testArrayOfString() throws ExecutionException, InterruptedException,
            IOException {

        ChronicleMapBuilder<CharSequence, I1> builder = ChronicleMapBuilder
                .of(CharSequence.class, I1.class)
                .entries(10);

        try (ChronicleMap<CharSequence, I1> map = newInstance(builder)) {

            {
                final I1 i1 = map.newValueInstance();
                i1.setStrAt(1, "Hello");
                i1.setStrAt(2, "World");
                map.put("Key1", i1);
            }

            {
                final I1 i1 = map.newValueInstance();
                i1.setStrAt(1, "Hello2");
                i1.setStrAt(2, "World2");
                map.put("Key2", i1);
            }

            {
                final I1 key = map.get("Key1");

                assertEquals("Hello", key.getStrAt(1));
                assertEquals("World", key.getStrAt(2));
            }

            {
                final I1 key = map.get("Key2");

                assertEquals("Hello2", key.getStrAt(1));
                assertEquals("World2", key.getStrAt(2));
            }

            // todo not currently supported for arrays
            // mapChecks();
        }
    }


    @Test
    public void testCharArrayValue() throws ExecutionException, InterruptedException, IOException {

        int valueSize = 10;

        char[] expected = new char[valueSize];
        Arrays.fill(expected, 'X');

        ChronicleMapBuilder<CharSequence, char[]> builder = ChronicleMapBuilder
                .of(CharSequence.class, char[].class);

        try (ChronicleMap<CharSequence, char[]> map = newInstance(builder)) {
            map.put("Key", expected);

            assertEquals(Chars.asList(expected), Chars.asList(map.get("Key")));
            mapChecks();
        }
    }

    @Test
    public void testByteArrayArrayValue()
            throws ExecutionException, InterruptedException, IOException {

        ChronicleMapBuilder<byte[], byte[][]> builder = ChronicleMapBuilder
                .of(byte[].class, byte[][].class)
                .averageKey("Key".getBytes())
                .averageValue(new byte[][]{"value1".getBytes(), "value2".getBytes()});

        try (ChronicleMap<byte[], byte[][]> map = newInstance(builder)) {
            byte[] bytes1 = "value1".getBytes();
            byte[] bytes2 = "value2".getBytes();
            byte[][] value = {bytes1, bytes2};
            map.put("Key".getBytes(), value);

            assertEquals(value, map.get("Key".getBytes()));
            mapChecks();
        }
    }

    @Test
    public void bondExample() throws IOException, InterruptedException {

        ChronicleMapBuilder builder = ChronicleMapBuilder.of(String.class, BondVOInterface.class)
                .averageKeySize(10);

        try (ChronicleMap<String, BondVOInterface> chm = newInstance(builder)) {
            BondVOInterface bondVO = chm.newValueInstance();
            try (net.openhft.chronicle.core.io.Closeable c =
                         chm.acquireContext("369604103", bondVO)) {
                bondVO.setIssueDate(parseYYYYMMDD("20130915"));
                bondVO.setMaturityDate(parseYYYYMMDD("20140915"));
                bondVO.setCoupon(5.0 / 100); // 5.0%

                BondVOInterface.MarketPx mpx930 = bondVO.getMarketPxIntraDayHistoryAt(0);
                mpx930.setAskPx(109.2);
                mpx930.setBidPx(106.9);

                BondVOInterface.MarketPx mpx1030 = bondVO.getMarketPxIntraDayHistoryAt(1);
                mpx1030.setAskPx(109.7);
                mpx1030.setBidPx(107.6);
            }

        }

    }

    @Test
    public void testLargeCharSequenceValueWriteOnly() throws ExecutionException, InterruptedException, IOException {

        int valueSize = 1000000;

        char[] expected = new char[valueSize];
        Arrays.fill(expected, 'X');

        ChronicleMapBuilder<CharSequence, char[]> builder = ChronicleMapBuilder
                .of(CharSequence.class, char[].class).entries(1)
                .constantValueSizeBySample(expected);

        try (ChronicleMap<CharSequence, char[]> map = newInstance(builder)) {
            map.put("Key", expected);
            mapChecks();
        }
    }


    @Test
    public void testEntrySpanningSeveralChunks()
            throws ExecutionException, InterruptedException, IOException {

        int salefactor = 100;
        int valueSize = 10 * salefactor;

        char[] expected = new char[valueSize];
        Arrays.fill(expected, 'X');

        ChronicleMapBuilder<CharSequence, char[]> builder = ChronicleMapBuilder
                .of(CharSequence.class, char[].class)
                .averageKeySize(10)
                .averageValueSize(10);

        try (ChronicleMap<CharSequence, char[]> map = newInstance(builder)) {
            map.put("Key", expected);
            mapChecks();
        }
    }





    @Test
    public void testKeyValueSizeBySample() throws ExecutionException, InterruptedException,
            IOException {

        ChronicleMapBuilder<CharSequence, CharSequence> builder = ChronicleMapBuilder
                .of(CharSequence.class, CharSequence.class)
                .averageKeySize("Key".length())
                .averageValueSize("Value".length())
                .entries(1);

        try (ChronicleMap<CharSequence, CharSequence> map = newInstance(builder)) {
            map.put("Key", "Value");
            mapChecks();
        }
    }


    @Test
    public void testLargeCharSequenceValue()
            throws ExecutionException, InterruptedException, IOException {

        int valueSize = 5_000_000;

        char[] expected = new char[valueSize];
        Arrays.fill(expected, 'X');

        ChronicleMapBuilder<CharSequence, char[]> builder = ChronicleMapBuilder
                .of(CharSequence.class, char[].class).entries(1)
                .constantValueSizeBySample(expected);

        try (ChronicleMap<CharSequence, char[]> map = newInstance(builder)) {
            map.put("Key", expected);
            Assert.assertArrayEquals(expected, map.get("Key"));
        }
    }

    @Test
    public void testStringStringMap() throws ExecutionException, InterruptedException,
            IOException {


        ChronicleMapBuilder<String, String> builder = ChronicleMapBuilder
                .of(String.class, String.class);

        try (ChronicleMap<String, String> map = newInstance(builder)) {
            map.put("Hello", "World");
            assertEquals("World", map.get("Hello"));

            assertEquals("New World", map.getMapped("Hello", new PrefixStringFunction("New ")));
            assertEquals(null, map.getMapped("No key", new PrefixStringFunction("New ")));
            mapChecks();
        }
    }


    private static class StringPrefixUnaryOperator
            implements BiFunction<String, String, String>, Serializable {

        private String prefix;

        StringPrefixUnaryOperator(final String prefix1) {
            prefix = prefix1;
        }

        @Override
        public String apply(String k, String v) {
            return prefix + v;
        }
    }


    @Test
    public void testStringStringMapMutableValue() throws ExecutionException, InterruptedException, IOException {

        ChronicleMapBuilder<String, String> builder = ChronicleMapBuilder
                .of(String.class, String.class);

        try (ChronicleMap<String, String> map = newInstance(builder)) {
            map.put("Hello", "World");
            map.computeIfPresent("Hello", new StringPrefixUnaryOperator("New "));
            mapChecks();
        }
    }

    @Test
    public void testCharSequenceMixingKeyTypes() throws ExecutionException, InterruptedException, IOException {

        ChronicleMapBuilder<CharSequence, CharSequence> builder = ChronicleMapBuilder
                .of(CharSequence.class, CharSequence.class);

        try (ChronicleMap<CharSequence, CharSequence> map = newInstance(builder)) {

            map.put("Hello", "World");
            map.put(new StringBuilder("Hello"), "World2");

            Assert.assertEquals("World2", map.get("Hello"));
            mapChecks();
        }
    }

    @Test
    public void testCharSequenceMixingValueTypes() throws ExecutionException, InterruptedException, IOException {

        ChronicleMapBuilder<CharSequence, CharSequence> builder = ChronicleMapBuilder
                .of(CharSequence.class, CharSequence.class);

        try (ChronicleMap<CharSequence, CharSequence> map = newInstance(builder)) {
            map.put("Hello", "World");
            map.put("Hello2", new StringBuilder("World2"));

            Assert.assertEquals("World2", map.get("Hello2"));
            Assert.assertEquals("World", map.get("Hello"));
            mapChecks();
        }
    }


    /**
     * CharSequence is more efficient when object creation is avoided.
     * * The key can only be on heap and variable length serialised.
     */
    @Ignore("This test fails because assumes StringBuilder is fed to lambdas, but actually " +
            "because interned in CharSequenceReader. Defer fixing this until Chronicle Map " +
            "serialization framework is moved from Lang Bytes to Chronicle Bytes")
    @Test
    public void testCharSequenceCharSequenceMap()
            throws ExecutionException, InterruptedException, IOException {


        ChronicleMapBuilder<CharSequence, CharSequence> builder = ChronicleMapBuilder
                .of(CharSequence.class, CharSequence.class);

        try (ChronicleMap<CharSequence, CharSequence> map = newInstance(builder)) {
            map.put("Hello", "World");
            StringBuilder key = new StringBuilder();
            key.append("key-").append(1);

            StringBuilder value = new StringBuilder();
            value.append("value-").append(1);
            map.put(key, value);
            assertEquals("value-1", map.get("key-1"));

            assertEquals(value, map.getUsing(key, value));
            assertEquals("value-1", value.toString());
            map.remove("key-1");
            assertNull(map.getUsing(key, value));


            assertEquals("New World", map.getMapped("Hello", s -> "New " + s));
            assertEquals(null, map.getMapped("No key",
                    (SerializableFunction<CharSequence, CharSequence>) s -> "New " + s));

            assertEquals("New World !!", map.computeIfPresent("Hello", (k, s) -> {
                ((StringBuilder) s).append(" !!");
                return "New " + s;
            }));

            assertEquals("New World !!", map.get("Hello").toString());

            assertEquals("New !!", map.computeIfPresent("no-key", (k, s) -> {
                ((StringBuilder) s).append("!!");
                return "New " + s;
            }));

            mapChecks();
        }
    }


    @Test
    public void testAcquireUsingWithCharSequence() throws IOException {


        ChronicleMapBuilder<CharSequence, CharSequence> builder = ChronicleMapBuilder
                .of(CharSequence.class, CharSequence.class);

        try (ChronicleMap<CharSequence, CharSequence> map = newInstance(builder)) {

            CharSequence using = map.newValueInstance();

            try (net.openhft.chronicle.core.io.Closeable c = map.acquireContext("1", using)) {
                assertTrue(using instanceof StringBuilder);
                ((StringBuilder) using).append("Hello World");
            }

            assertEquals("Hello World", map.get("1"));
            mapChecks();
        }
    }

    @Test
    public void testGetUsingWithIntValueNoValue() throws IOException {

        ChronicleMapBuilder<CharSequence, IntValue> builder = ChronicleMapBuilder
                .of(CharSequence.class, IntValue.class);

        try (ChronicleMap<CharSequence, IntValue> map = newInstance(builder)) {

            try (ExternalMapQueryContext<CharSequence, IntValue, ?> c = map.queryContext("1")) {
                assertNull(c.entry());
            }

            assertEquals(null, map.get("1"));
            mapChecks();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAcquireUsingImmutableUsing() throws IOException {

        ChronicleMapBuilder<IntValue, CharSequence> builder = ChronicleMapBuilder
                .of(IntValue.class, CharSequence.class);

        try (ChronicleMap<IntValue, CharSequence> map = newInstance(builder)) {


            IntValue using = map.newKeyInstance();
            using.setValue(1);

            try (Closeable c = map.acquireContext(using, "")) {
                assertTrue(using instanceof IntValue);
                using.setValue(1);
            }

            assertEquals(null, map.get("1"));
            mapChecks();
        }
    }

    @Test
    public void testAcquireUsingWithIntValueKeyStringBuilderValue() throws IOException {

        ChronicleMapBuilder<IntValue, StringBuilder> builder = ChronicleMapBuilder
                .of(IntValue.class, StringBuilder.class);

        try (ChronicleMap<IntValue, StringBuilder> map = newInstance(builder)) {


            IntValue key = map.newKeyInstance();
            key.setValue(1);

            StringBuilder using = map.newValueInstance();

            try (net.openhft.chronicle.core.io.Closeable c = map.acquireContext(key, using)) {
                using.append("Hello");
            }

            assertEquals("Hello", map.get(key).toString());
            mapChecks();
        }
    }

    @Test
    public void testAcquireUsingWithIntValueKey() throws IOException {

        ChronicleMapBuilder<IntValue, CharSequence> builder = ChronicleMapBuilder
                .of(IntValue.class, CharSequence.class);

        try (ChronicleMap<IntValue, CharSequence> map = newInstance(builder)) {

            IntValue key = map.newKeyInstance();
            key.setValue(1);

            CharSequence using = map.newValueInstance();

            try (net.openhft.chronicle.core.io.Closeable c = map.acquireContext(key, using)) {
                key.setValue(3);
                ((StringBuilder) using).append("Hello");
            }

            key.setValue(2);
            try (net.openhft.chronicle.core.io.Closeable c = map.acquireContext(key, using)) {
                ((StringBuilder) using).append("World");
            }

            key.setValue(1);
            assertEquals("Hello", map.get(key));
            mapChecks();
        }
    }

    @Test
    public void testAcquireUsingWithByteBufferBytesValue() throws IOException {
        ChronicleMapBuilder<IntValue, CharSequence> builder = ChronicleMapBuilder
                .of(IntValue.class, CharSequence.class);

        try (ChronicleMap<IntValue, CharSequence> map = newInstance(builder)) {

            IntValue key = map.newKeyInstance();
            key.setValue(1);

            ByteBufferBytes value = new ByteBufferBytes(ByteBuffer.allocate(10));
            value.limit(0);

            try (net.openhft.chronicle.core.io.Closeable c = map.acquireContext(key, value)) {
                assertTrue(key instanceof IntValue);
                assertTrue(value instanceof CharSequence);
            }

            assertTrue(map.get(key).length() == 0);
            mapChecks();
        }
    }


    /**
     * StringValue represents any bean which contains a String Value
     */
    @Test
    public void testStringValueStringValueMap() throws IOException {

        ChronicleMapBuilder<StringValue, StringValue> builder = ChronicleMapBuilder
                .of(StringValue.class, StringValue.class);

        try (ChronicleMap<StringValue, StringValue> map = newInstance(builder)) {
            StringValue key1 = map.newKeyInstance();
            StringValue key2 = map.newKeyInstance();
            StringValue value1 = map.newValueInstance();
            StringValue value2 = map.newValueInstance();

            key1.setValue(new StringBuilder("1"));
            value1.setValue("11");
            map.put(key1, value1);
            assertEquals(value1, map.get(key1));

            key2.setValue("2");
            value2.setValue(new StringBuilder("22"));
            map.put(key2, value2);
            assertEquals(value2, map.get(key2));

            mapChecks();

            StringBuilder sb = new StringBuilder();
            try (ExternalMapQueryContext<StringValue, StringValue, ?> c = map.queryContext(key1)) {
                MapEntry<StringValue, StringValue> entry = c.entry();
                assertNotNull(entry);
                StringValue v = entry.value().get();
                assertEquals("11", v.getValue());
                v.getUsingValue(sb);
                assertEquals("11", sb.toString());
            }

            mapChecks();

            try (ExternalMapQueryContext<StringValue, StringValue, ?> c = map.queryContext(key2)) {
                MapEntry<StringValue, StringValue> entry = c.entry();
                assertNotNull(entry);
                StringValue v = entry.value().get();
                assertEquals("22", v.getValue());
                v.getUsingValue(sb);
                assertEquals("22", sb.toString());
            }

            mapChecks();

            try (ExternalMapQueryContext<StringValue, StringValue, ?> c = map.queryContext(key1)) {
                MapEntry<StringValue, StringValue> entry = c.entry();
                assertNotNull(entry);
                StringValue v = entry.value().get();
                assertEquals("11", v.getValue());
                v.getUsingValue(sb);
                assertEquals("11", sb.toString());
            }

            mapChecks();

            try (ExternalMapQueryContext<StringValue, StringValue, ?> c = map.queryContext(key2)) {
                MapEntry<StringValue, StringValue> entry = c.entry();
                assertNotNull(entry);
                StringValue v = entry.value().get();
                assertEquals("22", v.getValue());
                v.getUsingValue(sb);
                assertEquals("22", sb.toString());
            }

            key1.setValue("3");
            try (ExternalMapQueryContext<StringValue, StringValue, ?> c = map.queryContext(key1)) {
                assertNotNull(c.absentEntry());
            }

            key2.setValue("4");
            try (ExternalMapQueryContext<StringValue, StringValue, ?> c = map.queryContext(key2)) {
                assertNotNull(c.absentEntry());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value1)) {
                assertEquals("", value1.getValue());
                value1.getUsingValue(sb);
                assertEquals("", sb.toString());
                sb.append(123);
                value1.setValue(sb);
            }

            mapChecks();

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value2)) {
                assertEquals("123", value2.getValue());
                value2.setValue(value2.getValue() + '4');
                assertEquals("1234", value2.getValue());
            }

            mapChecks();

            try (ExternalMapQueryContext<StringValue, StringValue, ?> c = map.queryContext(key1)) {
                MapEntry<StringValue, StringValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals("1234", entry.value().get().getValue());
            }

            mapChecks();

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value2)) {
                assertEquals("", value2.getValue());
                value2.getUsingValue(sb);
                assertEquals("", sb.toString());
                sb.append(123);
                value2.setValue(sb);
            }

            mapChecks();

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value1)) {
                assertEquals("123", value1.getValue());
                value1.setValue(value1.getValue() + '4');
                assertEquals("1234", value1.getValue());
            }

            mapChecks();

            try (ExternalMapQueryContext<StringValue, StringValue, ?> c = map.queryContext(key2)) {
                MapEntry<StringValue, StringValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals("1234", entry.value().get().getValue());
            }

            mapChecks();
        }
    }

    @Test
    public void testIntegerIntegerMap()
            throws ExecutionException, InterruptedException, IOException {

        ChronicleMapBuilder<Integer, Integer> builder = ChronicleMapBuilder
                .of(Integer.class, Integer.class);

        try (ChronicleMap<Integer, Integer> map = newInstance(builder)) {

            assertEquals(1, ((VanillaChronicleMap) map).maxChunksPerEntry);
            Integer key1;
            Integer key2;
            Integer value1;
            Integer value2;

            key1 = 1;
            value1 = 11;
            map.put(key1, value1);
            assertEquals(value1, map.get(key1));


            key2 = 2;
            value2 = 22;
            map.put(key2, value2);
            assertEquals(value2, map.get(key2));

            assertEquals((Integer) 11, map.get(key1));
            assertEquals((Integer) 22, map.get(key2));
            assertEquals(null, map.get(3));
            assertEquals(null, map.get(4));

            mapChecks();

            assertEquals((Integer) 110, map.getMapped(1, new SerializableFunction<Integer, Integer>() {
                @Override
                public Integer apply(Integer s) {
                    return 10 * s;
                }
            }));

            mapChecks();

            assertEquals(null, map.getMapped(-1, new SerializableFunction<Integer, Integer>() {
                @Override
                public Integer apply(Integer s) {
                    return 10 * s;
                }
            }));

            mapChecks();

            try {
                map.computeIfPresent(1, (k, s) -> s + 1);
            } catch (Exception todoMoreSpecificException) {
            }
            mapChecks();

        }
    }

    @Test
    public void testLongLongMap() throws ExecutionException, InterruptedException, IOException {


        ChronicleMapBuilder<Long, Long> builder = ChronicleMapBuilder
                .of(Long.class, Long.class);

        try (ChronicleMap<Long, Long> map = newInstance(builder)) {
//            assertEquals(16, entrySize(map));
//            assertEquals(1, ((VanillaChronicleMap) map).maxChunksPerEntry);
            map.put(1L, 11L);
            assertEquals((Long) 11L, map.get(1L));

            map.put(2L, 22L);
            assertEquals((Long) 22L, map.get(2L));

            assertEquals(null, map.get(3L));
            assertEquals(null, map.get(4L));

            mapChecks();

            assertEquals((Long) 110L, map.getMapped(1L, new SerializableFunction<Long, Long>() {
                @Override
                public Long apply(Long s) {
                    return 10 * s;
                }
            }));
            assertEquals(null, map.getMapped(-1L, (SerializableFunction<Long, Long>) s -> 10 * s));

            mapChecks();

            try {
                map.computeIfPresent(1L, (k, s) -> s + 1);
            } catch (Exception todoMoreSpecificException) {
            }

            mapChecks();
        }
    }

    @Test
    public void testDoubleDoubleMap() throws ExecutionException, InterruptedException, IOException {


        ChronicleMapBuilder<Double, Double> builder = ChronicleMapBuilder
                .of(Double.class, Double.class);

        try (ChronicleMap<Double, Double> map = newInstance(builder)) {

            assertEquals(1, ((VanillaChronicleMap) map).maxChunksPerEntry);
            map.put(1.0, 11.0);
            assertEquals((Double) 11.0, map.get(1.0));

            map.put(2.0, 22.0);
            assertEquals((Double) 22.0, map.get(2.0));

            assertEquals(null, map.get(3.0));
            assertEquals(null, map.get(4.0));

            assertEquals((Double) 110.0, map.getMapped(1.0, new SerializableFunction<Double, Double>() {
                @Override
                public Double apply(Double s) {
                    return 10 * s;
                }
            }));
            assertEquals(null, map.getMapped(-1.0, (SerializableFunction<Double, Double>) s -> 10 * s));

            try {
                map.computeIfPresent(1.0, (k, s) -> s + 1);

            } catch (Exception todoMoreSpecificException) {
            }


        }
    }

    @Test
    public void testByteArrayByteArrayMap()
            throws ExecutionException, InterruptedException, IOException {

        ChronicleMapBuilder<byte[], byte[]> builder = ChronicleMapBuilder
                .of(byte[].class, byte[].class).averageKeySize(4).averageValueSize(4)
                .entries(1000);

        try (ChronicleMap<byte[], byte[]> map = newInstance(builder)) {
            byte[] key1 = {1, 1, 1, 1};
            byte[] key2 = {2, 2, 2, 2};
            byte[] value1 = {11, 11, 11, 11};
            byte[] value2 = {22, 22, 22, 22};
            assertNull(map.put(key1, value1));
            assertTrue(Arrays.equals(value1, map.put(key1, value2)));
            assertTrue(Arrays.equals(value2, map.get(key1)));
            assertNull(map.get(key2));

            map.put(key1, value1);


            assertTrue(Arrays.equals(new byte[]{11, 11},
                    map.getMapped(key1, new SerializableFunction<byte[], byte[]>() {
                        @Override
                        public byte[] apply(byte[] s) {
                            return Arrays.copyOf(s, 2);
                        }
                    })));
            assertEquals(null, map.getMapped(key2, new SerializableFunction<byte[], byte[]>() {
                @Override
                public byte[] apply(byte[] s) {
                    return Arrays.copyOf(s, 2);
                }
            }));

            assertTrue(Arrays.equals(new byte[]{12, 10},
                    map.computeIfPresent(key1, (k, s) -> {
                        s[0]++;
                        s[1]--;
                        return Arrays.copyOf(s, 2);
                    })));

            byte[] a2 = map.get(key1);
            assertTrue(Arrays.equals(new byte[]{12, 10}, a2));


        }
    }


    @Test
    public void testByteBufferByteBufferDefaultKeyValueMarshaller() throws ExecutionException,
            InterruptedException, IOException {

        ChronicleMapBuilder<ByteBuffer, ByteBuffer> builder = ChronicleMapBuilder
                .of(ByteBuffer.class, ByteBuffer.class)
                .averageKeySize(8)
                .averageValueSize(8)
                .entries(1000);

        try (ChronicleMap<ByteBuffer, ByteBuffer> map = newInstance(builder)) {


            ByteBuffer key1 = ByteBuffer.wrap(new byte[]{1, 1, 1, 1});
            ByteBuffer key2 = ByteBuffer.wrap(new byte[]{2, 2, 2, 2});
            ByteBuffer value1 = ByteBuffer.wrap(new byte[]{11, 11, 11, 11});
            ByteBuffer value2 = ByteBuffer.wrap(new byte[]{22, 22, 22, 22});
            assertNull(map.put(key1, value1));
            assertBBEquals(value1, map.put(key1, value2));
            assertBBEquals(value2, map.get(key1));
            assertNull(map.get(key2));


            map.put(key1, value1);

            mapChecks();
        }
    }


    @Test
    public void testByteBufferByteBufferMap()
            throws ExecutionException, InterruptedException, IOException {

        ChronicleMapBuilder<ByteBuffer, ByteBuffer> builder = ChronicleMapBuilder
                .of(ByteBuffer.class, ByteBuffer.class)
                .averageKeySize(8)
                .averageValueSize(8)
                .entries(1000);

        try (ChronicleMap<ByteBuffer, ByteBuffer> map = newInstance(builder)) {


            ByteBuffer key1 = ByteBuffer.wrap(new byte[]{1, 1, 1, 1});
            ByteBuffer key2 = ByteBuffer.wrap(new byte[]{2, 2, 2, 2});
            ByteBuffer value1 = ByteBuffer.wrap(new byte[]{11, 11, 11, 11});
            ByteBuffer value2 = ByteBuffer.wrap(new byte[]{22, 22, 22, 22});
            assertNull(map.put(key1, value1));
            assertBBEquals(value1, map.put(key1, value2));
            assertBBEquals(value2, map.get(key1));
            assertNull(map.get(key2));

            final SerializableFunction<ByteBuffer, ByteBuffer> function =
                    new SerializableFunction<ByteBuffer, ByteBuffer>() {
                        @Override
                        public ByteBuffer apply(ByteBuffer s) {
                            ByteBuffer slice = s.slice();
                            slice.limit(2);
                            return slice;
                        }
                    };

            map.put(key1, value1);
            assertBBEquals(ByteBuffer.wrap(new byte[]{11, 11}), map.getMapped(key1, function));
            assertEquals(null, map.getMapped(key2, function));
            mapChecks();
            assertBBEquals(ByteBuffer.wrap(new byte[]{12, 10}),
                    map.computeIfPresent(key1, (k, s) -> {
                        s.put(0, (byte) (s.get(0) + 1));
                        s.put(1, (byte) (s.get(1) - 1));
                        return function.apply(s);
                    }));

            assertBBEquals(ByteBuffer.wrap(new byte[]{12, 10}), map.get(key1));

            mapChecks();

            map.put(key1, value1);
            map.put(key2, value2);
            ByteBuffer valueA = ByteBuffer.allocateDirect(8);
            ByteBuffer valueB = ByteBuffer.allocate(8);
//            assertBBEquals(value1, valueA);
            try (ExternalMapQueryContext<ByteBuffer, ByteBuffer, ?> c = map.queryContext(key1)) {
                MapEntry<ByteBuffer, ByteBuffer> entry = c.entry();
                assertNotNull(entry);
                assertBBEquals(value1, entry.value().getUsing(valueA));
            }
            try (ExternalMapQueryContext<ByteBuffer, ByteBuffer, ?> c = map.queryContext(key2)) {
                MapEntry<ByteBuffer, ByteBuffer> entry = c.entry();
                assertNotNull(entry);
                assertBBEquals(value2, entry.value().getUsing(valueA));
            }

            try (ExternalMapQueryContext<ByteBuffer, ByteBuffer, ?> c = map.queryContext(key1)) {
                MapEntry<ByteBuffer, ByteBuffer> entry = c.entry();
                assertNotNull(entry);
                assertBBEquals(value1, entry.value().getUsing(valueB));
            }
            try (ExternalMapQueryContext<ByteBuffer, ByteBuffer, ?> c = map.queryContext(key2)) {
                MapEntry<ByteBuffer, ByteBuffer> entry = c.entry();
                assertNotNull(entry);
                assertBBEquals(value2, entry.value().getUsing(valueB));
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, valueA)) {
                assertBBEquals(value1, valueA);
                appendMode(valueA);
                valueA.clear();
                valueA.putInt(12345);
                valueA.flip();
            }


            value1.clear();
            value1.putInt(12345);
            value1.flip();


            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, valueB)) {
                assertBBEquals(value1, valueB);
                appendMode(valueB);
                valueB.putShort((short) 12345);
                valueB.flip();
            }

            try (ExternalMapQueryContext<ByteBuffer, ByteBuffer, ?> c = map.queryContext(key1)) {
                MapEntry<ByteBuffer, ByteBuffer> entry = c.entry();
                assertNotNull(entry);

                ByteBuffer bb1 = ByteBuffer.allocate(8);
                bb1.put(value1);
                bb1.putShort((short) 12345);
                bb1.flip();
                assertBBEquals(bb1, entry.value().getUsing(valueA));
            }

            mapChecks();
        }
    }

    private static void appendMode(ByteBuffer valueA) {
        valueA.position(valueA.limit());
        valueA.limit(valueA.capacity());
    }

    @Test
    public void testByteBufferDirectByteBufferMap()
            throws ExecutionException, InterruptedException, IOException {

        ChronicleMapBuilder<ByteBuffer, ByteBuffer> builder = ChronicleMapBuilder
                .of(ByteBuffer.class, ByteBuffer.class)
                .averageKeySize(5).averageValueSize(5)
                .entries(1000);

        try (ChronicleMap<ByteBuffer, ByteBuffer> map = newInstance(builder)) {


            ByteBuffer key1 = ByteBuffer.wrap(new byte[]{1, 1, 1, 1});
            ByteBuffer key2 = ByteBuffer.wrap(new byte[]{2, 2, 2, 2});
            ByteBuffer value1 = ByteBuffer.allocateDirect(4);
            value1.put(new byte[]{11, 11, 11, 11});
            value1.flip();
            ByteBuffer value2 = ByteBuffer.allocateDirect(4);
            value2.put(new byte[]{22, 22, 22, 22});
            value2.flip();
            assertNull(map.put(key1, value1));
            assertBBEquals(value1, map.put(key1, value2));
            assertBBEquals(value2, map.get(key1));
            assertNull(map.get(key2));
            map.put(key1, value1);
            mapChecks();
            final SerializableFunction<ByteBuffer, ByteBuffer> function =
                    s -> {
                        ByteBuffer slice = s.slice();
                        slice.limit(2);
                        return slice;
                    };
            assertBBEquals(ByteBuffer.wrap(new byte[]{11, 11}), map.getMapped(key1, function));
            assertEquals(null, map.getMapped(key2, function));
            mapChecks();
            assertBBEquals(ByteBuffer.wrap(new byte[]{12, 10}),
                    map.computeIfPresent(key1, (k, s) -> {
                        s.put(0, (byte) (s.get(0) + 1));
                        s.put(1, (byte) (s.get(1) - 1));
                        return function.apply(s);
                    }));

            assertBBEquals(ByteBuffer.wrap(new byte[]{12, 10}), map.get(key1));
            mapChecks();
        }
    }

    private void assertBBEquals(ByteBuffer bb1, ByteBuffer bb2) {
        assertEquals(bb1.remaining(), bb2.remaining());
        for (int i = 0; i < bb1.remaining(); i++)
            assertEquals(bb1.get(bb1.position() + i), bb2.get(bb2.position() + i));
    }

    @Test
    public void testIntValueIntValueMap() throws IOException {

        ChronicleMapBuilder<IntValue, IntValue> builder = ChronicleMapBuilder
                .of(IntValue.class, IntValue.class);

        try (ChronicleMap<IntValue, IntValue> map = newInstance(builder)) {
            // this may change due to alignment
//            assertEquals(8, entrySize(map));
            assertEquals(1, ((VanillaChronicleMap) map).maxChunksPerEntry);
            IntValue key1 = map.newKeyInstance();
            IntValue key2 = map.newKeyInstance();
            IntValue value1 = map.newValueInstance();
            IntValue value2 = map.newValueInstance();


            key1.setValue(1);
            value1.setValue(11);
            map.put(key1, value1);
            assertEquals(value1, map.get(key1));

            key2.setValue(2);
            value2.setValue(22);
            map.put(key2, value2);
            assertEquals(value2, map.get(key2));

            try (ExternalMapQueryContext<IntValue, IntValue, ?> c = map.queryContext(key1)) {
                MapEntry<IntValue, IntValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            // TODO review -- the previous version of this block:
            // acquiring for value1, comparing value2 -- as intended?
//            try (ReadContext rc = map.getUsingLocked(key2, value1)) {
//                assertTrue(rc.present());
//                assertEquals(22, value2.getValue());
//            }
            try (ExternalMapQueryContext<IntValue, IntValue, ?> c = map.queryContext(key2)) {
                MapEntry<IntValue, IntValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<IntValue, IntValue, ?> c = map.queryContext(key1)) {
                MapEntry<IntValue, IntValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<IntValue, IntValue, ?> c = map.queryContext(key2)) {
                MapEntry<IntValue, IntValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            key1.setValue(3);
            try (ExternalMapQueryContext<IntValue, IntValue, ?> c = map.queryContext(key1)) {
                assertNotNull(c.absentEntry());
            }
            key2.setValue(4);
            try (ExternalMapQueryContext<IntValue, IntValue, ?> c = map.queryContext(key2)) {
                assertNotNull(c.absentEntry());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value1)) {
                assertEquals(0, value1.getValue());
                value1.addValue(123);
                assertEquals(123, value1.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value2)) {
                assertEquals(123, value2.getValue());
                value2.addValue(1230 - 123);
                assertEquals(1230, value2.getValue());
            }
            try (ExternalMapQueryContext<IntValue, IntValue, ?> c = map.queryContext(key1)) {
                MapEntry<IntValue, IntValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value2)) {
                assertEquals(0, value2.getValue());
                value2.addValue(123);
                assertEquals(123, value2.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value1)) {
                assertEquals(123, value1.getValue());
                value1.addValue(1230 - 123);
                assertEquals(1230, value1.getValue());
            }
            try (ExternalMapQueryContext<IntValue, IntValue, ?> c = map.queryContext(key2)) {
                MapEntry<IntValue, IntValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue());
            }
            mapChecks();

        }
    }

    /**
     * For unsigned int -> unsigned int entries, the key can be on heap or off heap.
     */
    @Test
    public void testUnsignedIntValueUnsignedIntValueMap() throws IOException {

        ChronicleMapBuilder<UnsignedIntValue, UnsignedIntValue> builder = ChronicleMapBuilder
                .of(UnsignedIntValue.class, UnsignedIntValue.class);

        try (ChronicleMap<UnsignedIntValue, UnsignedIntValue> map = newInstance(builder)) {

            assertEquals(1, ((VanillaChronicleMap) map).maxChunksPerEntry);
            UnsignedIntValue key1 = map.newKeyInstance();
            UnsignedIntValue value1 = map.newValueInstance();

            key1.setValue(1);
            value1.setValue(11);
            map.put(key1, value1);
            assertEquals(value1, map.get(key1));

            key1 = map.newKeyInstance();
            UnsignedIntValue key2 = map.newKeyInstance();
            value1 = map.newValueInstance();
            UnsignedIntValue value2 = map.newValueInstance();

            key1.setValue(1);
            value1.setValue(11);
            map.put(key1, value1);
            assertEquals(value1, map.get(key1));

            key2.setValue(2);
            value2.setValue(22);
            map.put(key2, value2);
            assertEquals(value2, map.get(key2));

            try (ExternalMapQueryContext<UnsignedIntValue, UnsignedIntValue, ?> c =
                         map.queryContext(key1)) {
                MapEntry<UnsignedIntValue, UnsignedIntValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            // TODO review suspicious block
//            try (ReadContext rc = map.getUsingLocked(key2, value1)) {
//                assertTrue(rc.present());
//                assertEquals(22, value2.getValue());
//            }
            try (ExternalMapQueryContext<UnsignedIntValue, UnsignedIntValue, ?> c =
                         map.queryContext(key2)) {
                MapEntry<UnsignedIntValue, UnsignedIntValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<UnsignedIntValue, UnsignedIntValue, ?> c =
                         map.queryContext(key1)) {
                MapEntry<UnsignedIntValue, UnsignedIntValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<UnsignedIntValue, UnsignedIntValue, ?> c =
                         map.queryContext(key2)) {
                MapEntry<UnsignedIntValue, UnsignedIntValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            key1.setValue(3);
            try (ExternalMapQueryContext<UnsignedIntValue, UnsignedIntValue, ?> c =
                         map.queryContext(key1)) {
                assertNotNull(c.absentEntry());
            }
            key2.setValue(4);
            try (ExternalMapQueryContext<UnsignedIntValue, UnsignedIntValue, ?> c =
                         map.queryContext(key2)) {
                assertNotNull(c.absentEntry());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value1)) {
                assertEquals(0, value1.getValue());
                value1.addValue(123);
                assertEquals(123, value1.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value2)) {
                assertEquals(123, value2.getValue());
                value2.addValue(1230 - 123);
                assertEquals(1230, value2.getValue());
            }
            try (ExternalMapQueryContext<UnsignedIntValue, UnsignedIntValue, ?> c =
                         map.queryContext(key1)) {
                MapEntry<UnsignedIntValue, UnsignedIntValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value2)) {
                assertEquals(0, value2.getValue());
                value2.addValue(123);
                assertEquals(123, value2.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value1)) {
                assertEquals(123, value1.getValue());
                value1.addValue(1230 - 123);
                assertEquals(1230, value1.getValue());
            }
            try (ExternalMapQueryContext<UnsignedIntValue, UnsignedIntValue, ?> c =
                         map.queryContext(key2)) {
                MapEntry<UnsignedIntValue, UnsignedIntValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue());
            }
            mapChecks();
        }
    }

    /**
     * For int values, the key can be on heap or off heap.
     */
    @Test
    public void testIntValueShortValueMap() throws IOException {

        ChronicleMapBuilder<IntValue, ShortValue> builder = ChronicleMapBuilder
                .of(IntValue.class, ShortValue.class);

        try (ChronicleMap<IntValue, ShortValue> map = newInstance(builder)) {

            // this may change due to alignment
            // assertEquals(6, entrySize(map));


            //     assertEquals(1, ((VanillaChronicleMap) map).maxChunksPerEntry);
            IntValue key1 = map.newKeyInstance();
            IntValue key2 = map.newKeyInstance();
            ShortValue value1 = map.newValueInstance();
            ShortValue value2 = map.newValueInstance();

            key1.setValue(1);
            value1.setValue((short) 11);
            map.put(key1, value1);
            assertEquals(value1, map.get(key1));

            key2.setValue(2);
            value2.setValue((short) 22);
            map.put(key2, value2);
            assertEquals(value2, map.get(key2));

            try (ExternalMapQueryContext<?, ShortValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, ShortValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            // TODO the same as above.
//            try (ReadContext rc = map.getUsingLocked(key2, value1)) {
//                assertTrue(rc.present());
//                assertEquals(22, value2.getValue());
//            }
            try (ExternalMapQueryContext<?, ShortValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, ShortValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<?, ShortValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, ShortValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<?, ShortValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, ShortValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            key1.setValue(3);
            try (ExternalMapQueryContext<?, ShortValue, ?> c = map.queryContext(key1)) {
                assertNotNull(c.absentEntry());
            }
            key2.setValue(4);
            try (ExternalMapQueryContext<?, ShortValue, ?> c = map.queryContext(key2)) {
                assertNotNull(c.absentEntry());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value1)) {
                assertEquals(0, value1.getValue());
                value1.addValue((short) 123);
                assertEquals(123, value1.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value2)) {
                assertEquals(123, value2.getValue());
                value2.addValue((short) (1230 - 123));
                assertEquals(1230, value2.getValue());
            }
            try (ExternalMapQueryContext<?, ShortValue, ?> c =
                         map.queryContext(key1)) {
                MapEntry<?, ShortValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value2)) {
                assertEquals(0, value2.getValue());
                value2.addValue((short) 123);
                assertEquals(123, value2.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value1)) {
                assertEquals(123, value1.getValue());
                value1.addValue((short) (1230 - 123));
                assertEquals(1230, value1.getValue());
            }
            try (ExternalMapQueryContext<?, ShortValue, ?> c =
                         map.queryContext(key2)) {
                MapEntry<?, ShortValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue());
            }
            mapChecks();
        }
    }

    /**
     * For int -> unsigned short values, the key can be on heap or off heap.
     */
    @Test
    public void testIntValueUnsignedShortValueMap() throws IOException {

        ChronicleMapBuilder<IntValue, UnsignedShortValue> builder = ChronicleMapBuilder
                .of(IntValue.class, UnsignedShortValue.class);

        try (ChronicleMap<IntValue, UnsignedShortValue> map = newInstance(builder)) {


            // this may change due to alignment
            // assertEquals(8, entrySize(map));
            assertEquals(1, ((VanillaChronicleMap) map).maxChunksPerEntry);
            IntValue key1 = map.newKeyInstance();
            IntValue key2 = map.newKeyInstance();
            UnsignedShortValue value1 = map.newValueInstance();
            UnsignedShortValue value2 = map.newValueInstance();

            key1.setValue(1);
            value1.setValue(11);
            map.put(key1, value1);
            assertEquals(value1, map.get(key1));

            key2.setValue(2);
            value2.setValue(22);
            map.put(key2, value2);
            assertEquals(value2, map.get(key2));

            try (ExternalMapQueryContext<?, UnsignedShortValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, UnsignedShortValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            // TODO the same as above.
//            try (ReadContext rc = map.getUsingLocked(key2, value1)) {
//                assertTrue(rc.present());
//                assertEquals(22, value2.getValue());
//            }
            try (ExternalMapQueryContext<?, UnsignedShortValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, UnsignedShortValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<?, UnsignedShortValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, UnsignedShortValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<?, UnsignedShortValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, UnsignedShortValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            key1.setValue(3);
            try (ExternalMapQueryContext<?, UnsignedShortValue, ?> c = map.queryContext(key1)) {
                assertNotNull(c.absentEntry());
            }
            key2.setValue(4);
            try (ExternalMapQueryContext<?, UnsignedShortValue, ?> c = map.queryContext(key2)) {
                assertNotNull(c.absentEntry());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value1)) {
                assertEquals(0, value1.getValue());
                value1.addValue(123);
                assertEquals(123, value1.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value2)) {
                assertEquals(123, value2.getValue());
                value2.addValue(1230 - 123);
                assertEquals(1230, value2.getValue());
            }
            try (ExternalMapQueryContext<?, UnsignedShortValue, ?> c =
                         map.queryContext(key1)) {
                MapEntry<?, UnsignedShortValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value2)) {
                assertEquals(0, value2.getValue());
                value2.addValue(123);
                assertEquals(123, value2.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value1)) {
                assertEquals(123, value1.getValue());
                value1.addValue(1230 - 123);
                assertEquals(1230, value1.getValue());
            }
            try (ExternalMapQueryContext<?, UnsignedShortValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, UnsignedShortValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue());
            }
            mapChecks();
        }
    }

    /**
     * For int values, the key can be on heap or off heap.
     */
    @Test
    public void testIntValueCharValueMap() throws IOException {

        ChronicleMapBuilder<IntValue, CharValue> builder = ChronicleMapBuilder
                .of(IntValue.class, CharValue.class);

        try (ChronicleMap<IntValue, CharValue> map = newInstance(builder)) {


            assertEquals(1, ((VanillaChronicleMap) map).maxChunksPerEntry);
            IntValue key1 = map.newKeyInstance();
            IntValue key2 = map.newKeyInstance();
            CharValue value1 = map.newValueInstance();
            CharValue value2 = map.newValueInstance();

            key1.setValue(1);
            value1.setValue((char) 11);
            map.put(key1, value1);
            assertEquals(value1, map.get(key1));

            key2.setValue(2);
            value2.setValue((char) 22);
            map.put(key2, value2);
            assertEquals(value2, map.get(key2));

            try (ExternalMapQueryContext<?, CharValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, CharValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            // TODO The same as above
//            try (ReadContext rc = map.getUsingLocked(key2, value1)) {
//                assertTrue(rc.present());
//                assertEquals(22, value2.getValue());
//            }
            try (ExternalMapQueryContext<?, CharValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, CharValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<?, CharValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, CharValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<?, CharValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, CharValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            key1.setValue(3);
            try (ExternalMapQueryContext<?, CharValue, ?> c = map.queryContext(key1)) {
                assertNotNull(c.absentEntry());
            }
            key2.setValue(4);
            try (ExternalMapQueryContext<?, CharValue, ?> c = map.queryContext(key2)) {
                assertNotNull(c.absentEntry());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value1)) {
                assertEquals('\0', value1.getValue());
                value1.setValue('@');
                assertEquals('@', value1.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c = map.acquireContext(key1, value2)) {
                assertEquals('@', value2.getValue());
                value2.setValue('#');
                assertEquals('#', value2.getValue());
            }
            try (ExternalMapQueryContext<IntValue, CharValue, ?> c = map.queryContext(key1)) {
                MapEntry<IntValue, CharValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals('#', entry.value().get().getValue());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value2)) {
                assertEquals('\0', value2.getValue());
                value2.setValue(';');
                assertEquals(';', value2.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value1)) {
                assertEquals(';', value1.getValue());
                value1.setValue('[');
                assertEquals('[', value1.getValue());
            }
            try (ExternalMapQueryContext<IntValue, CharValue, ?> c = map.queryContext(key2)) {
                MapEntry<IntValue, CharValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals('[', entry.value().get().getValue());
            }
        }
    }

    /**
     * For int-> byte entries, the key can be on heap or off heap.
     */
    @Test
    public void testIntValueUnsignedByteMap() throws IOException {

        ChronicleMapBuilder<IntValue, UnsignedByteValue> builder = ChronicleMapBuilder
                .of(IntValue.class, UnsignedByteValue.class);

        try (ChronicleMap<IntValue, UnsignedByteValue> map = newInstance(builder)) {


            // TODO should be 5, but shorter fields based on range doesn't seem to be implemented
            // on data value generation level yet
            //assertEquals(8, entrySize(map)); this may change due to alignmented
            assertEquals(1, ((VanillaChronicleMap) map).maxChunksPerEntry);

            IntValue key1 = map.newKeyInstance();
            IntValue key2 = map.newKeyInstance();
            UnsignedByteValue value1 = map.newValueInstance();
            UnsignedByteValue value2 = map.newValueInstance();


            key1.setValue(1);
            value1.setValue(11);
            map.put(key1, value1);
            assertEquals(value1, map.get(key1));

            key2.setValue(2);
            value2.setValue(22);
            map.put(key2, value2);
            assertEquals(value2, map.get(key2));

            try (ExternalMapQueryContext<?, UnsignedByteValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, UnsignedByteValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            // TODO the same as above
//            try (ReadContext rc = map.getUsingLocked(key2, value1)) {
//                assertTrue(rc.present());
//                assertEquals(22, value2.getValue());
//            }
            try (ExternalMapQueryContext<?, UnsignedByteValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, UnsignedByteValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<?, UnsignedByteValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, UnsignedByteValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<?, UnsignedByteValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, UnsignedByteValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            key1.setValue(3);
            try (ExternalMapQueryContext<?, UnsignedByteValue, ?> c = map.queryContext(key1)) {
                assertNotNull(c.absentEntry());
            }
            key2.setValue(4);
            try (ExternalMapQueryContext<?, UnsignedByteValue, ?> c = map.queryContext(key2)) {
                assertNotNull(c.absentEntry());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value1)) {
                assertEquals(0, value1.getValue());
                value1.addValue(234);
                assertEquals(234, value1.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value2)) {
                assertEquals(234, value2.getValue());
                value2.addValue(-100);
                assertEquals(134, value2.getValue());
            }
            try (ExternalMapQueryContext<?, UnsignedByteValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, UnsignedByteValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(134, entry.value().get().getValue());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value2)) {
                assertEquals(0, value2.getValue());
                value2.addValue((byte) 123);
                assertEquals(123, value2.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value1)) {
                assertEquals(123, value1.getValue());
                value1.addValue((byte) -111);
                assertEquals(12, value1.getValue());
            }
            try (ExternalMapQueryContext<?, UnsignedByteValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, UnsignedByteValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(12, entry.value().get().getValue());
            }
            mapChecks();
        }
    }

    /**
     * For int values, the key can be on heap or off heap.
     */
    @Test
    public void testIntValueBooleanValueMap() throws IOException {

        ChronicleMapBuilder<IntValue, BooleanValue> builder = ChronicleMapBuilder
                .of(IntValue.class, BooleanValue.class);

        try (ChronicleMap<IntValue, BooleanValue> map = newInstance(builder)) {


            assertEquals(1, ((VanillaChronicleMap) map).maxChunksPerEntry);

            IntValue key1 = map.newKeyInstance();
            IntValue key2 = map.newKeyInstance();
            BooleanValue value1 = map.newValueInstance();
            BooleanValue value2 = map.newValueInstance();


            key1.setValue(1);
            value1.setValue(true);
            map.put(key1, value1);
            assertEquals(value1, map.get(key1));

            key2.setValue(2);
            value2.setValue(false);
            map.put(key2, value2);
            assertEquals(value2, map.get(key2));

            try (ExternalMapQueryContext<?, BooleanValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, BooleanValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(true, entry.value().get().getValue());
            }
            // TODO the same as above. copy paste, copy paste, copy-paste...
//            try (ReadContext rc = map.getUsingLocked(key2, value1)) {
//                assertTrue(rc.present());
//                assertEquals(false, value2.getValue());
//            }
            try (ExternalMapQueryContext<?, BooleanValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, BooleanValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(false, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<?, BooleanValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, BooleanValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(true, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<?, BooleanValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, BooleanValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(false, entry.value().get().getValue());
            }
            key1.setValue(3);
            try (ExternalMapQueryContext<?, BooleanValue, ?> c = map.queryContext(key1)) {
                assertNotNull(c.absentEntry());
            }
            key2.setValue(4);
            try (ExternalMapQueryContext<?, BooleanValue, ?> c = map.queryContext(key2)) {
                assertNotNull(c.absentEntry());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value1)) {
                assertEquals(false, value1.getValue());
                value1.setValue(true);
                assertEquals(true, value1.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value2)) {
                assertEquals(true, value2.getValue());
                value2.setValue(false);
                assertEquals(false, value2.getValue());
            }
            try (ExternalMapQueryContext<?, BooleanValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, BooleanValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(false, entry.value().get().getValue());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value2)) {
                assertEquals(false, value2.getValue());
                value2.setValue(true);
                assertEquals(true, value2.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value1)) {
                assertEquals(true, value1.getValue());
                value1.setValue(false);
                assertEquals(false, value1.getValue());
            }
            try (ExternalMapQueryContext<?, BooleanValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, BooleanValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(false, entry.value().get().getValue());
            }
            mapChecks();
        }
    }

    /**
     * For float values, the key can be on heap or off heap.
     */
    @Test
    public void testFloatValueFloatValueMap() throws IOException {

        ChronicleMapBuilder<FloatValue, FloatValue> builder = ChronicleMapBuilder
                .of(FloatValue.class, FloatValue.class);

        try (ChronicleMap<FloatValue, FloatValue> map = newInstance(builder)) {

            assertEquals(1, ((VanillaChronicleMap) map).maxChunksPerEntry);

            FloatValue key1 = map.newKeyInstance();
            FloatValue key2 = map.newKeyInstance();
            FloatValue value1 = map.newValueInstance();
            FloatValue value2 = map.newValueInstance();


            key1.setValue(1);
            value1.setValue(11);
            map.put(key1, value1);
            assertEquals(value1, map.get(key1));

            key2.setValue(2);
            value2.setValue(22);
            map.put(key2, value2);
            assertEquals(value2, map.get(key2));

            try (ExternalMapQueryContext<?, FloatValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, FloatValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue(), 0);
            }
            // TODO see above
//            try (ReadContext rc = map.getUsingLocked(key2, value1)) {
//                assertTrue(rc.present());
//                assertEquals(22, value2.getValue(), 0);
//            }
            try (ExternalMapQueryContext<?, FloatValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, FloatValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue(), 0);
            }
            try (ExternalMapQueryContext<?, FloatValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, FloatValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue(), 0);
            }
            try (ExternalMapQueryContext<?, FloatValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, FloatValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue(), 0);
            }
            key1.setValue(3);
            try (ExternalMapQueryContext<?, FloatValue, ?> c = map.queryContext(key1)) {
                assertNotNull(c.absentEntry());
            }
            key2.setValue(4);
            try (ExternalMapQueryContext<?, FloatValue, ?> c = map.queryContext(key2)) {
                assertNotNull(c.absentEntry());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value1)) {
                assertEquals(0, value1.getValue(), 0);
                value1.addValue(123);
                assertEquals(123, value1.getValue(), 0);
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value2)) {
                assertEquals(123, value2.getValue(), 0);
                value2.addValue(1230 - 123);
                assertEquals(1230, value2.getValue(), 0);
            }
            try (ExternalMapQueryContext<?, FloatValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, FloatValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue(), 0);
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value2)) {
                assertEquals(0, value2.getValue(), 0);
                value2.addValue(123);
                assertEquals(123, value2.getValue(), 0);
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value1)) {
                assertEquals(123, value1.getValue(), 0);
                value1.addValue(1230 - 123);
                assertEquals(1230, value1.getValue(), 0);
            }
            try (ExternalMapQueryContext<?, FloatValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, FloatValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue(), 0);
            }
            mapChecks();
        }
    }


    /**
     * For double values, the key can be on heap or off heap.
     */
    @Test
    public void testDoubleValueDoubleValueMap() throws IOException {

        ChronicleMapBuilder<DoubleValue, DoubleValue> builder = ChronicleMapBuilder
                .of(DoubleValue.class, DoubleValue.class);

        try (ChronicleMap<DoubleValue, DoubleValue> map = newInstance(builder)) {

            // this may change due to alignment
            //assertEquals(16, entrySize(map));

            assertEquals(1, ((VanillaChronicleMap) map).maxChunksPerEntry);

            DoubleValue key1 = map.newKeyInstance();
            DoubleValue key2 = map.newKeyInstance();
            DoubleValue value1 = map.newValueInstance();
            DoubleValue value2 = map.newValueInstance();


            key1.setValue(1);
            value1.setValue(11);
            assertEquals(null, map.get(key1));

            map.put(key1, value1);
            assertEquals(value1, map.get(key1));

            key2.setValue(2);
            value2.setValue(22);
            map.put(key2, value2);
            assertEquals(value2, map.get(key2));

            try (ExternalMapQueryContext<?, DoubleValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, DoubleValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue(), 0);
            }
//            try (ReadContext rc = map.getUsingLocked(key2, value1)) {
//                assertTrue(rc.present());
//                assertEquals(22, value2.getValue(), 0);
//            }
            try (ExternalMapQueryContext<?, DoubleValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, DoubleValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue(), 0);
            }
            try (ExternalMapQueryContext<?, DoubleValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, DoubleValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue(), 0);
            }
            try (ExternalMapQueryContext<?, DoubleValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, DoubleValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue(), 0);
            }
            key1.setValue(3);
            try (ExternalMapQueryContext<?, DoubleValue, ?> c = map.queryContext(key1)) {
                assertNotNull(c.absentEntry());
            }
            key2.setValue(4);
            try (ExternalMapQueryContext<?, DoubleValue, ?> c = map.queryContext(key2)) {
                assertNotNull(c.absentEntry());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value1)) {
                assertEquals(0, value1.getValue(), 0);
                value1.addValue(123);
                assertEquals(123, value1.getValue(), 0);
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value2)) {
                assertEquals(123, value2.getValue(), 0);
                value2.addValue(1230 - 123);
                assertEquals(1230, value2.getValue(), 0);
            }
            try (ExternalMapQueryContext<?, DoubleValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, DoubleValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue(), 0);
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value2)) {
                assertEquals(0, value2.getValue(), 0);
                value2.addValue(123);
                assertEquals(123, value2.getValue(), 0);
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value1)) {
                assertEquals(123, value1.getValue(), 0);
                value1.addValue(1230 - 123);
                assertEquals(1230, value1.getValue(), 0);
            }
            try (ExternalMapQueryContext<?, DoubleValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, DoubleValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue(), 0);
            }
            mapChecks();
        }
    }

    /**
     * For long values, the key can be on heap or off heap.
     */
    @Test
    public void testLongValueLongValueMap() throws IOException {

        ChronicleMapBuilder<LongValue, LongValue> builder = ChronicleMapBuilder
                .of(LongValue.class, LongValue.class);

        try (ChronicleMap<LongValue, LongValue> map = newInstance(builder)) {

            // this may change due to alignment
            // assertEquals(16, entrySize(map));
            assertEquals(1, ((VanillaChronicleMap) map).maxChunksPerEntry);

            LongValue key1 = map.newKeyInstance();
            LongValue key2 = map.newKeyInstance();
            LongValue value1 = map.newValueInstance();
            LongValue value2 = map.newValueInstance();


            key1.setValue(1);
            value1.setValue(11);
            assertEquals(null, map.get(key1));
            map.put(key1, value1);

            key2.setValue(2);
            value2.setValue(22);
            map.put(key2, value2);
            assertEquals(value2, map.get(key2));

            try (ExternalMapQueryContext<?, LongValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, LongValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            // TODO see above
//            try (ReadContext rc = map.getUsingLocked(key2, value1)) {
//                assertTrue(rc.present());
//                assertEquals(22, value2.getValue());
//            }
            try (ExternalMapQueryContext<?, LongValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, LongValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<?, LongValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, LongValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(11, entry.value().get().getValue());
            }
            try (ExternalMapQueryContext<?, LongValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, LongValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(22, entry.value().get().getValue());
            }
            key1.setValue(3);
            try (ExternalMapQueryContext<?, LongValue, ?> c = map.queryContext(key1)) {
                assertNotNull(c.absentEntry());
            }
            key2.setValue(4);
            try (ExternalMapQueryContext<?, LongValue, ?> c = map.queryContext(key2)) {
                assertNotNull(c.absentEntry());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value1)) {
                assertEquals(0, value1.getValue());
                value1.addValue(123);
                assertEquals(123, value1.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key1, value2)) {
                assertEquals(123, value2.getValue());
                value2.addValue(1230 - 123);
                assertEquals(1230, value2.getValue());
            }
            try (ExternalMapQueryContext<?, LongValue, ?> c = map.queryContext(key1)) {
                MapEntry<?, LongValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue());
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value2)) {
                assertEquals(0, value2.getValue());
                value2.addValue(123);
                assertEquals(123, value2.getValue());
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext(key2, value1)) {
                assertEquals(123, value1.getValue());
                value1.addValue(1230 - 123);
                assertEquals(1230, value1.getValue());
            }
            try (ExternalMapQueryContext<?, LongValue, ?> c = map.queryContext(key2)) {
                MapEntry<?, LongValue> entry = c.entry();
                assertNotNull(entry);
                assertEquals(1230, entry.value().get().getValue());
            }
            mapChecks();
        }
    }

    /**
     * For beans, the key can be on heap or off heap as long as the bean is not variable length.
     */
    @Test
    public void testBeanBeanMap() {
    }

    @Test
    public void testListValue() throws IOException {

        ChronicleMapBuilder<String, List<String>> builder = ChronicleMapBuilder
                .of(String.class, (Class<List<String>>) (Class) List.class)
                .valueMarshaller(ListMarshaller.of(new StringMarshaller(8)));


        try (ChronicleMap<String, List<String>> map = newInstance(builder)) {
            map.put("1", Collections.<String>emptyList());
            map.put("2", asList("two-A"));

            List<String> list1 = new ArrayList<>();
            try (net.openhft.chronicle.core.io.Closeable c = map.acquireContext("1", list1)) {
                list1.add("one");
                assertEquals(asList("one"), list1);
            }
            List<String> list2 = new ArrayList<>();
            try (ExternalMapQueryContext<String, List<String>, ?> c = map.queryContext("1")) {
                MapEntry<String, List<String>> entry = c.entry();
                assertNotNull(entry);
                assertEquals(asList("one"), entry.value().getUsing(list2));
            }

            try (ExternalMapQueryContext<String, List<String>, ?> c = map.queryContext("2")) {
                MapEntry<String, List<String>> entry = c.entry();
                assertNotNull(entry);
                entry.value().getUsing(list2);
                list2.add("two-B");     // this is not written as it only a read context
                assertEquals(asList("two-A", "two-B"), list2);
            }

            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext("2", list1)) {
                list1.add("two-C");
                assertEquals(asList("two-A", "two-C"), list1);
            }

            try (ExternalMapQueryContext<String, List<String>, ?> c = map.queryContext("2")) {
                MapEntry<String, List<String>> entry = c.entry();
                assertNotNull(entry);
                assertEquals(asList("two-A", "two-C"), entry.value().getUsing(list2));
            }
        }
    }

    @Test
    public void testSetValue() throws IOException {

        ChronicleMapBuilder<String, Set<String>> builder = ChronicleMapBuilder
                .of(String.class, (Class<Set<String>>) (Class) Set.class)
                .valueMarshaller(SetMarshaller.of(new StringMarshaller(8)));


        try (ChronicleMap<String, Set<String>> map = newInstance(builder)) {
            map.put("1", Collections.<String>emptySet());
            map.put("2", new LinkedHashSet<String>(asList("one")));

            Set<String> list1 = new LinkedHashSet<>();
            try (net.openhft.chronicle.core.io.Closeable c = map.acquireContext("1", list1)) {
                list1.add("two");
                assertEquals(new LinkedHashSet<String>(asList("two")), list1);
            }
            Set<String> list2 = new LinkedHashSet<>();
            try (ExternalMapQueryContext<String, Set<String>, ?> c = map.queryContext("1")) {
                MapEntry<String, Set<String>> entry = c.entry();
                assertNotNull(entry);
                assertEquals(new LinkedHashSet<>(asList("two")), entry.value().getUsing(list2));
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext("2", list1)) {
                list1.add("three");
                assertEquals(new LinkedHashSet<String>(asList("one", "three")), list1);
            }
            try (ExternalMapQueryContext<String, Set<String>, ?> c =
                         map.queryContext("2")) {
                MapEntry<String, Set<String>> entry = c.entry();
                assertNotNull(entry);
                assertEquals(new LinkedHashSet<>(asList("one", "three")),
                        entry.value().getUsing(list2));
            }

            for (Map.Entry<String, Set<String>> entry : map.entrySet()) {
                entry.getKey();
                entry.getValue();
            }

            mapChecks();
        }
    }


    @Test
    public void testMapStringStringValue() throws IOException {

        ChronicleMapBuilder<String, Map<String, String>> builder = ChronicleMapBuilder
                .of(String.class, (Class<Map<String, String>>) (Class) Map.class)
                .valueMarshaller(MapMarshaller.of(new StringMarshaller(16), new StringMarshaller
                        (16)));


        try (ChronicleMap<String, Map<String, String>> map = newInstance(builder)) {
            map.put("1", Collections.<String, String>emptyMap());
            map.put("2", mapOf("one", "uni"));

            Map<String, String> map1 = new LinkedHashMap<>();
            try (net.openhft.chronicle.core.io.Closeable c = map.acquireContext("1", map1)) {
                map1.put("two", "bi");
                assertEquals(mapOf("two", "bi"), map1);
            }
            Map<String, String> map2 = new LinkedHashMap<>();
            try (ExternalMapQueryContext<String, Map<String, String>, ?> c =
                         map.queryContext("1")) {
                MapEntry<String, Map<String, String>> entry = c.entry();
                assertNotNull(entry);
                assertEquals(mapOf("two", "bi"), entry.value().getUsing(map2));
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext("2", map1)) {
                map1.put("three", "tri");
                assertEquals(mapOf("one", "uni", "three", "tri"), map1);
            }
            try (ExternalMapQueryContext<String, Map<String, String>, ?> c =
                         map.queryContext("2")) {
                MapEntry<String, Map<String, String>> entry = c.entry();
                assertNotNull(entry);
                assertEquals(mapOf("one", "uni", "three", "tri"), entry.value().getUsing(map2));
            }
            mapChecks();
        }
    }

    @Test
    public void testMapStringIntegerValue() throws IOException {

        ChronicleMapBuilder<String, Map<String, Integer>> builder = ChronicleMapBuilder
                .of(String.class, (Class<Map<String, Integer>>) (Class) Map.class)
                .valueMarshaller(MapMarshaller.of(new StringMarshaller(16), new
                        GenericEnumMarshaller<Integer>(Integer.class, 16)));

        try (ChronicleMap<String, Map<String, Integer>> map = newInstance(builder)) {
            map.put("1", Collections.<String, Integer>emptyMap());
            map.put("2", mapOf("one", 1));

            Map<String, Integer> map1 = new LinkedHashMap<>();
            try (net.openhft.chronicle.core.io.Closeable c = map.acquireContext("1", map1)) {
                map1.put("two", 2);
                assertEquals(mapOf("two", 2), map1);
            }
            Map<String, Integer> map2 = new LinkedHashMap<>();
            try (ExternalMapQueryContext<String, Map<String, Integer>, ?> c =
                         map.queryContext("1")) {
                MapEntry<String, Map<String, Integer>> entry = c.entry();
                assertNotNull(entry);
                assertEquals(mapOf("two", 2), entry.value().getUsing(map2));
            }
            try (net.openhft.chronicle.core.io.Closeable c =
                         map.acquireContext("2", map1)) {
                map1.put("three", 3);
                assertEquals(mapOf("one", 1, "three", 3), map1);
            }
            try (ExternalMapQueryContext<String, Map<String, Integer>, ?> c =
                         map.queryContext("2")) {
                MapEntry<String, Map<String, Integer>> entry = c.entry();
                assertNotNull(entry);
                assertEquals(mapOf("one", 1, "three", 3), entry.value().getUsing(map2));
            }
            mapChecks();
        }
    }

    @Test
    public void testMapStringIntegerValueWithoutListMarshallers() throws IOException {
        ChronicleMapBuilder<String, Map<String, Integer>> builder = ChronicleMapBuilder
                .of(String.class, (Class<Map<String, Integer>>) (Class) Map.class);
        try (ChronicleMap<String, Map<String, Integer>> map = newInstance(builder)) {
            map.put("1", Collections.<String, Integer>emptyMap());
            map.put("2", mapOf("two", 2));

            assertEquals(mapOf("two", 2), map.get("2"));
            mapChecks();
        }
    }

    public static <K, V> Map<K, V> mapOf(K k, V v, Object... keysAndValues) {
        Map<K, V> ret = new LinkedHashMap<>();
        ret.put(k, v);
        for (int i = 0; i < keysAndValues.length - 1; i += 2) {
            Object key = keysAndValues[i];
            Object value = keysAndValues[i + 1];
            ret.put((K) key, (V) value);
        }
        return ret;
    }

    @Test
    public void testGeneratedDataValue() throws IOException {

        ChronicleMapBuilder<String, IBean> builder = ChronicleMapBuilder
                .of(String.class, IBean.class).averageKeySize(5).entries(1000);
        try (ChronicleMap<String, IBean> map = newInstance(builder)) {

            IBean iBean = map.newValueInstance();
            try (net.openhft.chronicle.core.io.Closeable c = map.acquireContext("1", iBean)) {
                iBean.setDouble(1.2);
                iBean.setLong(2);
                iBean.setInt(4);
                IBean.Inner innerAt = iBean.getInnerAt(1);
                innerAt.setMessage("Hello world");
            }

            assertEquals(2, map.get("1").getLong());
            assertEquals("Hello world", map.get("1").getInnerAt(1).getMessage());
            mapChecks();
        }
    }

    @Test
    public void testBytesMarshallable() throws IOException {
        ChronicleMapBuilder<IData, IData> builder = ChronicleMapBuilder
                .of(IData.class, IData.class)
                        // TODO if the keyMarshaller is set, the map.newKeyInstance() blows up.
//                .keyMarshaller(new BytesMarshallableMarshaller<>(IData.class))
                .entries(1000);
        try (ChronicleMap<IData, IData> map = newInstance(builder)) {
            for (int i = 0; i < 100; i++) {
                IData key = map.newKeyInstance();
                IData value = map.newValueInstance();
                key.setText("key-" + i);
                key.setNumber(i);
                value.setNumber(i);
                value.setText("value-" + i);
                map.put(key, value);
                // check the map is still valid.
                map.entrySet().toString();
            }
        }
    }

    @Test
    public void testBytesMarshallable2() throws IOException {
        ChronicleMapBuilder<Data, Data> builder = ChronicleMapBuilder
                .of(Data.class, Data.class)
                .keyMarshaller(new BytesMarshallableMarshaller<>(Data.class))
                .actualChunkSize(64)
                .entries(1000);
        try (ChronicleMap<Data, Data> map = newInstance(builder)) {
            for (int i = 0; i < 100; i++) {
                Data key = map.newKeyInstance();
                Data value = map.newValueInstance();
                key.setText("key-" + i);
                key.setNumber(i);
                value.setNumber(i);
                value.setText("value-" + i);
                map.put(key, value);
                // check the map is still valid.
                map.entrySet().toString();
            }
        }
    }
}

interface IData extends BytesMarshallable {
    void setText(@MaxSize String text);

    void setNumber(int number);

    String getText();

    int getNumber();
}

class Data implements IData, BytesMarshallable {
    static final long MAGIC = 0x8081828384858687L;
    static final long MAGIC2 = 0xa0a1a2a3a4a5a6a7L;

    String text;
    int number;

    @Override
    public String getText() {
        return text;
    }

    @Override
    public void setText(String text) {
        this.text = text;
    }

    @Override
    public int getNumber() {
        return number;
    }

    @Override
    public void setNumber(int number) {
        this.number = number;
    }

    @Override
    public void readMarshallable(@NotNull Bytes in) throws IllegalStateException {
        long magic = in.readLong();
        if (magic != MAGIC)
            throw new AssertionError("Start " + Long.toHexString(magic));
        text = in.readUTFΔ();
        number = in.readInt();
        long magic2 = in.readLong();
        if (magic2 != MAGIC2)
            throw new AssertionError("End " + Long.toHexString(magic2));
    }

    @Override
    public void writeMarshallable(@NotNull Bytes out) {
        out.writeLong(MAGIC);
        out.writeUTFΔ(text);
        out.writeInt(number);
        out.writeLong(MAGIC2);
    }
}

enum ToString implements SerializableFunction<Object, String> {
    INSTANCE;

    @Override
    public String apply(Object o) {
        return String.valueOf(o);
    }

}

interface IBean {
    long getLong();

    void setLong(long num);

    double getDouble();

    void setDouble(double d);

    int getInt();

    void setInt(int i);

    void setInnerAt(@MaxSize(7) int index, Inner inner);

    Inner getInnerAt(int index);

    /* nested interface - empowering an Off-Heap hierarchical “TIER of prices”
    as array[ ] value */
    interface Inner {

        String getMessage();

        void setMessage(@MaxSize(20) String px);

    }
}
