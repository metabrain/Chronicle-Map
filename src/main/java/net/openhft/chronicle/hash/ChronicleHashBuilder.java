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

package net.openhft.chronicle.hash;

import net.openhft.chronicle.hash.replication.ReplicableEntry;
import net.openhft.chronicle.hash.replication.SingleChronicleHashReplication;
import net.openhft.chronicle.hash.replication.TcpTransportAndNetworkConfig;
import net.openhft.chronicle.hash.replication.TimeProvider;
import net.openhft.chronicle.hash.serialization.*;
import net.openhft.chronicle.map.ChronicleMap;
import net.openhft.chronicle.map.ChronicleMapBuilder;
import net.openhft.chronicle.set.ChronicleSet;
import net.openhft.chronicle.set.ChronicleSetBuilder;
import net.openhft.lang.io.Bytes;
import net.openhft.lang.io.serialization.*;
import net.openhft.lang.io.serialization.impl.AllocateInstanceObjectFactory;
import net.openhft.lang.io.serialization.impl.NewInstanceObjectFactory;
import net.openhft.lang.io.serialization.impl.VanillaBytesMarshallerFactory;
import net.openhft.lang.model.Byteable;
import org.jetbrains.annotations.NotNull;

import java.io.Externalizable;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * This interface defines the meaning of configurations, common to {@link
 * ChronicleMapBuilder} and {@link ChronicleSetBuilder}, i.
 * e. <i>Chronicle hash container</i> configurations.
 *
 * <p>{@code ChronicleHashBuilder} is mutable. Configuration methods mutate the builder and return
 * <i>the builder itself</i> back to support chaining pattern, rather than the builder copies with
 * the corresponding configuration changed. To make an independent configuration, {@linkplain
 * #clone} the builder.
 *
 * <p><a name="low-level-config"></a>There are some "low-level" configurations in this builder,
 * that require deep understanding of the Chronicle implementation design to be properly used.
 * Know what you do. These configurations are applied as-is, without extra round-ups, adjustments,
 * etc.
 *
 * @param <K> the type of keys in hash containers, created by this builder
 * @param <H> the container type, created by this builder, i. e. {@link ChronicleMap} or {@link
 *            ChronicleSet}
 * @param <B> the concrete builder type, i. e. {@link ChronicleMapBuilder}
 *            or {@link ChronicleSetBuilder}
 */
public interface ChronicleHashBuilder<K, H extends ChronicleHash<K, ?, ?, ?>,
        B extends ChronicleHashBuilder<K, H, B>> extends Cloneable {

    /**
     * Clones this builder. Useful for configuration persisting, because {@code
     * ChronicleHashBuilder}s are mutable and changed on each configuration method call. Original
     * and cloned builders are independent.
     *
     * @return a new clone of this builder
     */
    B clone();

    /**
     * Set minimum number of segments in hash containers, constructed by this builder. See
     * concurrencyLevel in {@link ConcurrentHashMap}.
     *
     * @param minSegments the minimum number of segments in containers, constructed by this builder
     * @return this builder object back
     */
    B minSegments(int minSegments);

    /**
     * Configures the average number of bytes, taken by serialized form of keys, put into hash
     * containers, created by this builder. However, in many cases {@link #averageKey(Object)} might
     * be easier to use and more reliable. If key size is always the same, call {@link
     * #constantKeySizeBySample(Object)} method instead of this one.
     *
     * <p>{@code ChronicleHashBuilder} implementation heuristically chooses
     * {@linkplain #actualChunkSize(int) the actual chunk size} based on this configuration, that,
     * however, might result to quite high internal fragmentation, i. e. losses because only
     * integral number of chunks could be allocated for the entry. If you want to avoid this, you
     * should manually configure the actual chunk size in addition to this average key size
     * configuration, which is anyway needed.
     *
     * <p>If key is a boxed primitive type or {@link Byteable} subclass, i. e. if key size is known
     * statically, it is automatically accounted and shouldn't be specified by user.
     *
     * <p>Calling this method clears any previous {@link #constantKeySizeBySample(Object)} and
     * {@link #averageKey(Object)} configurations.
     *
     * @param averageKeySize the average number of bytes, taken by serialized form of keys
     * @return this builder back
     * @throws IllegalStateException if key size is known statically and shouldn't be configured
     *         by user
     * @throws IllegalArgumentException if the given {@code keySize} is non-positive
     * @see #averageKey(Object)
     * @see #constantKeySizeBySample(Object)
     * @see #actualChunkSize(int)
     */
    B averageKeySize(double averageKeySize);

    /**
     * Configures the average number of bytes, taken by serialized form of keys, put into hash
     * containers, created by this builder, by serializing the given {@code averageKey} using
     * the configured {@link #keyMarshallers(BytesWriter, BytesReader) keys marshallers}.
     * In some cases, {@link #averageKeySize(double)} might be easier to use, than constructing the
     * "average key". If key size is always the same, call {@link #constantKeySizeBySample(
     * Object)} method instead of this one.
     *
     * <p>{@code ChronicleHashBuilder} implementation heuristically chooses
     * {@linkplain #actualChunkSize(int) the actual chunk size} based on this configuration, that,
     * however, might result to quite high internal fragmentation, i. e. losses because only
     * integral number of chunks could be allocated for the entry. If you want to avoid this, you
     * should manually configure the actual chunk size in addition to this average key size
     * configuration, which is anyway needed.
     *
     * <p>If key is a boxed primitive type or {@link Byteable} subclass, i. e. if key size is known
     * statically, it is automatically accounted and shouldn't be specified by user.
     *
     * <p>Calling this method clears any previous {@link #constantKeySizeBySample(Object)} and
     * {@link #averageKeySize(double)} configurations.
     *
     * @param averageKey the average (by footprint in serialized form) key, is going to be put
     *                   into the hash containers, created by this builder
     * @return this builder back
     * @throws NullPointerException if the given {@code averageKey} is {@code null}
     * @see #averageKeySize(double)
     * @see #constantKeySizeBySample(Object)
     * @see #actualChunkSize(int)
     */
    B averageKey(K averageKey);

    /**
     * Configures the constant number of bytes, taken by serialized form of keys, put into hash
     * containers, created by this builder. This is done by providing the {@code sampleKey}, all
     * keys should take the same number of bytes in serialized form, as this sample object.
     *
     * <p>If keys are of boxed primitive type or {@link Byteable} subclass, i. e. if key size is
     * known statically, it is automatically accounted and this method shouldn't be called.
     *
     * <p>If key size varies, method {@link #averageKeySize(double)} should be called instead of
     * this one.
     *
     * <p>Calling this method clears any previous {@link #averageKey(Object)} and
     * {@link #averageKeySize(double)} configurations.
     *
     * @param sampleKey the sample key
     * @return this builder back
     * @see #averageKeySize(double)
     */
    B constantKeySizeBySample(K sampleKey);

    /**
     * Configures the size in bytes of allocation unit of hash container instances, created by this
     * builder.
     *
     * <p>{@link ChronicleMap} and {@link ChronicleSet} store their data off-heap, so it is required
     * to serialize key (and values, in {@code ChronicleMap} case) (unless they are direct {@link
     * Byteable} instances). Serialized key bytes (+ serialized value bytes, in {@code ChronicleMap}
     * case) + some metadata bytes comprise "entry space", which {@code ChronicleMap} or {@code
     * ChronicleSet} should allocate. So <i>chunk size</i> is the minimum allocation portion in the
     * hash containers, created by this builder. E. g. if chunk size is 100, the created container
     * could only allocate 100, 200, 300... bytes for an entry. If say 150 bytes of entry space are
     * required by the entry, 200 bytes will be allocated, 150 used and 50 wasted. This is called
     * internal fragmentation.
     *
     * <p>To minimize memory overuse and improve speed, you should pay decent attention to this
     * configuration. Alternatively, you can just trust the heuristics and doesn't configure
     * the chunk size.
     *
     * <p>Specify chunk size so that most entries would take from 5 to several dozens of chunks.
     * However, remember that operations with entries that span several chunks are a bit slower,
     * than with entries which take a single chunk. Particularly avoid entries to take more than
     * 64 chunks.
     *
     * <p>Example: if values in your {@code ChronicleMap} are adjacency lists of some social graph,
     * where nodes are represented as {@code long} ids, and adjacency lists are serialized in
     * efficient manner, for example as {@code long[]} arrays. Typical number of connections is
     * 100-300, maximum is 3000. In this case chunk size of
     * 30 * (8 bytes for each id) = 240 bytes would be a good choice: <pre>{@code
     * Map<Long, long[]> socialGraph = ChronicleMapOnHeapUpdatableBuilder
     *     .of(Long.class, long[].class)
     *     .entries(1_000_000_000L)
     *     .averageValueSize(150 * 8) // 150 is average adjacency list size
     *     .actualChunkSize(30 * 8) // average 5-6 chunks per entry
     *     .create();}</pre>
     *
     * <p>This is a <a href="#low-level-config">low-level configuration</a>. The configured number
     * of bytes is used as-is, without anything like round-up to the multiple of 8 or 16, or any
     * other adjustment.
     *
     * @param actualChunkSize the "chunk size" in bytes
     * @return this builder back
     * @see #entries(long)
     * @see #maxChunksPerEntry(int)
     */
    B actualChunkSize(int actualChunkSize);

    /**
     * Configures how many chunks a single entry, inserted into {@code ChronicleHash}es, created
     * by this builder, could take. If you try to insert larger entry, {@link IllegalStateException}
     * is fired. This is useful as self-check, that you configured chunk size right and you
     * keys (and values, in {@link ChronicleMap} case) take expected number of bytes. For example,
     * if {@link #constantKeySizeBySample(Object)} is configured or key size is statically known
     * to be constant (boxed primitives, data value generated implementations, {@link Byteable}s,
     * etc.), and the same for value objects in {@code ChronicleMap} case, max chunks per entry
     * is configured to 1, to ensure keys and values are actually constantly-sized.
     *
     * @param maxChunksPerEntry how many chunks a single entry could span at most
     * @return this builder back
     * @throws IllegalArgumentException if the given {@code maxChunksPerEntry} is lesser than 1
     *         or greater than 64
     * @see #actualChunkSize(int)
     */
    B maxChunksPerEntry(int maxChunksPerEntry);

    /**
     * Configures the target number of entries, that is going be inserted into the hash containers,
     * created by this builder. If {@link #maxBloatFactor(double)} is configured to {code 1.0}
     * (and this is by default), this number of entries is also the maximum. If you try to insert
     * more entries, than the configured {@code maxBloatFactor}, multiplied by the given number of
     * {@code entries}, {@link IllegalStateException} <i>might</i> be thrown.
     *
     * <p>This configuration should represent the expected maximum number of entries in a stable
     * state, {@link #maxBloatFactor(double) maxBloatFactor} - the maximum bloat up coefficient,
     * during exceptional bursts.
     *
     * <p>To be more precise - try to configure the {@code entries} so, that the created hash
     * container is going to serve about 99% requests being less or equal than this number
     * of entries in size.
     *
     * <p><b>You shouldn't put additional margin over the actual target number of entries.</b>
     * This bad practice was popularized by {@link HashMap#HashMap(int)} and {@link
     * HashSet#HashSet(int)} constructors, which accept <i>capacity</i>, that should be multiplied
     * by <i>load factor</i> to obtain the actual maximum expected number of entries.
     * {@code ChronicleMap} and {@code ChronicleSet} don't have a notion of load factor.
     *
     * <p>The default target number of entries is 2^20 (~ 1 million).
     *
     * @param entries the target size of the maps or sets, created by this builder
     * @return this builder back
     * @throws IllegalArgumentException if the given {@code entries} number is non-positive
     * @see #maxBloatFactor(double)
     */
    B entries(long entries);

    /**
     * Configures the maximum number of times, the hash containers, created by this builder,
     * are allowed to grow in size beyond the configured {@linkplain #entries(long) target number
     * of entries}.
     *
     * <p>{@link #entries(long)} should represent the expected maximum number of entries in a stable
     * state, {@code maxBloatFactor} - the maximum bloat up coefficient, during exceptional bursts.
     *
     * <p>This configuration should be used for self-checking. Even if you configure impossibly
     * large {@code maxBloatFactor}, the created {@code ChronicleHash}, of cause, will be still
     * operational, and even won't allocate any extra resources before they are actually needed.
     * But when the {@code ChronicleHash} grows beyond the configured {@link #entries(long)}, it
     * could start to serve requests progressively slower. If you insert new entries into
     * {@code ChronicleHash} infinitely, due to a bug in your business logic code, or the
     * ChronicleHash configuration, and if you configure the ChronicleHash to grow infinitely, you
     * will have a terribly slow and fat, but operational application, instead of a fail with
     * {@code IllegalStateException}, which will quickly show you, that there is a bug in you
     * application.
     *
     * <p>The default maximum bloat factor factor is {@code 1.0} - i. e. "no bloat is expected".
     *
     * <p>It is strongly advised not to configure {@code maxBloatFactor} to more than {@code 10.0},
     * almost certainly, you either should configure {@code ChronicleHash}es completely differently,
     * or this data structure doesn't fit you case.
     *
     * @param maxBloatFactor the maximum number ot times, the created hash container is supposed
     *                       to bloat up beyond the {@link #entries(long)}
     * @return this builder back
     * @throws IllegalArgumentException if the given {@code maxBloatFactor} is NaN, lesser than 1.0
     * or greater than 1000.0 (one thousand)
     * @see #entries(long)
     */
    B maxBloatFactor(double maxBloatFactor);

    /**
     * In addition to {@link #maxBloatFactor(double) maxBloatFactor(1.0)}, that <i>does not</i>
     * guarantee that segments won't tier (due to bad hash distribution or natural variance),
     * configuring {@code allowSegmentTiering(false)} makes Chronicle Hashes, created by this
     * builder, to throw {@code IllegalStateException} immediately when some segment overflows.
     *
     * <p>Useful exactly for testing hash distribution and variance of segment filling.
     *
     * <p>Default is {@code true}, segments are allowed to tier.
     *
     * <p>When configured to {@code false}, {@link #maxBloatFactor(double)} configuration becomes
     * irrelevant, because effectively no bloat is allowed.
     *
     * @param allowSegmentTiering if {@code true}, when a segment overflows a next tier
     *                             is allocated to accommodate new entries
     * @return this builder back
     */
    B allowSegmentTiering(boolean allowSegmentTiering);

    /**
     * Configures probabilistic fraction of segments, which shouldn't become tiered, if Chronicle
     * Hash size is {@link #entries(long)}, assuming hash code distribution of the keys, inserted
     * into configured Chronicle Hash, is good.
     *
     * <p>The last caveat means that the configured percentile and affects segment size relying on
     * Poisson distribution law, if inserted entries (keys) fall into all segments randomly. If
     * e. g. the keys, inserted into the Chronicle Hash, are purposely selected to collide by
     * a certain range of hash code bits, so that they all fall into the same segment (a DOS
     * attacker might do this), this segment is obviously going to be tiered.
     *
     * <p>This configuration affects the actual number of segments, if {@link #entries(long)} and
     * {@link #entriesPerSegment(long)} or {@link #actualChunksPerSegment(long)} are configured. It
     * affects the actual number of entries/chunks per segment, if {@link #entries(long)} and
     * {@link #actualSegments(int)} are configured. If all 4 configurations, mentioned in this
     * paragraph, are specified, {@code nonTieredSegmentsPercentile} is irrelevant.
     *
     * <p>Default value is 0.99999, i. e. if hash code distribution of the keys is good, only one
     * segment of 100K is tiered on average. If your segment size is small and you want to improve
     * memory footprint of Chronicle Hash (probably compromising latency percentiles), you might
     * want to configure more "relaxed" value, e. g. 0.99.
     *
     * @param nonTieredSegmentsPercentile Fraction of segments which shouldn't be tiered
     * @return this builder back
     * @throws IllegalArgumentException if {@code nonTieredSegmentsPercentile} is out of (0.5, 1.0)
     * range (both bounds are excluded)
     */
    B nonTieredSegmentsPercentile(double nonTieredSegmentsPercentile);

    /**
     * Configures the actual maximum number entries, that could be inserted into any single segment
     * of the hash containers, created by this builder. Configuring both the actual number of
     * entries per segment and {@linkplain #actualSegments(int) actual segments} replaces a single
     * {@link #entries(long)} configuration.
     *
     * <p>This is a <a href="#low-level-config">low-level configuration</a>.
     *
     * @param entriesPerSegment the actual maximum number entries per segment in the
     *                                hash containers, created by this builder
     * @return this builder back
     * @see #entries(long)
     * @see #actualSegments(int)
     */
    B entriesPerSegment(long entriesPerSegment);

    /**
     * Configures the actual number of chunks, that will be reserved for any single segment of the
     * hash containers, created by this builder. This configuration is a lower-level version of
     * {@link #entriesPerSegment(long)}. Makes sense only if {@link #actualChunkSize(int)},
     * {@link #actualSegments(int)} and {@link #entriesPerSegment(long)} are also configured
     * manually.
     *
     * @param actualChunksPerSegment the actual number of segments, reserved per segment in the
     *                               hash containers, created by this builder
     * @return this builder back
     */
    B actualChunksPerSegment(long actualChunksPerSegment);

    /**
     * Configures the actual number of segments in the hash containers, created by this builder.
     * With {@linkplain #entriesPerSegment(long) actual number of segments}, this
     * configuration replaces a single {@link #entries(long)} call.
     *
     * <p>This is a <a href="#low-level-config">low-level configuration</a>. The configured number
     * is used as-is, without anything like round-up to the closest power of 2.
     *
     * @param actualSegments the actual number of segments in hash containers, created by
     *                       this builder
     * @return this builder back
     * @see #minSegments(int)
     * @see #entriesPerSegment(long)
     */
    B actualSegments(int actualSegments);

    /**
     * Configures a time provider, used by hash containers, created by this builder, for needs of
     * replication consensus protocol (conflicting data updates resolution).
     *
     * <p>Default time provider uses system time ({@link System#currentTimeMillis()}) in
     * <i>microsecond</i> precision.
     *
     * @param timeProvider a new time provider for replication needs
     * @return this builder back
     * @see #replication(SingleChronicleHashReplication)
     */
    B timeProvider(TimeProvider timeProvider);

    /**
     * Configures timeout after which entries, marked as removed in the Chronicle Hash, constructed
     * by this builder, are allowed to be completely removed from the data structure. In replicated
     * Chronicle nodes, when {@code remove()} on the key is called, the corresponding entry
     * is not immediately erased from the data structure, to let the distributed system eventually
     * converge on some value for this key (or converge on the fact, that this key is removed).
     * Chronicle Hash watch in runtime after the entries, and if one is removed and not updated
     * in any way for this {@code removedEntryCleanupTimeout}, Chronicle is allowed to remove this
     * entry completely from the data structure. This timeout should depend on your distributed
     * system topology, and typical replication latencies, that should be determined experimentally.
     *
     * <p>Default timeout is 1 minute.
     *
     * @param removedEntryCleanupTimeout timeout, after which stale removed entries could be erased
     *                                   from Chronicle Hash data structure completely
     * @param unit time unit, in which the timeout is given
     * @return this builder back
     * @throws IllegalArgumentException is the specified timeout is less than 1 millisecond
     * @see #cleanupRemovedEntries(boolean)
     * @see ReplicableEntry#doRemoveCompletely()
     */
    B removedEntryCleanupTimeout(long removedEntryCleanupTimeout, TimeUnit unit);

    /**
     * Configures if replicated Chronicle Hashes, constructed by this builder, should
     * completely erase entries, removed some time ago. See {@link #removedEntryCleanupTimeout(
     * long, TimeUnit)} for more details on this mechanism.
     *
     * <p>Default value is {@code true} -- old removed entries are erased with 1 second timeout.
     *
     * @param cleanupRemovedEntries if stale removed entries should be purged from Chronicle Hash
     * @return this builder back
     * @see #removedEntryCleanupTimeout(long, TimeUnit)
     * @see ReplicableEntry#doRemoveCompletely()
     */
    B cleanupRemovedEntries(boolean cleanupRemovedEntries);

    /**
     * Configures a {@link BytesMarshallerFactory} to be used with {@link
     * BytesMarshallableSerializer}, which is a default {@link #objectSerializer ObjectSerializer},
     * to serialize/deserialize data to/from off-heap memory in hash containers, created by this
     * builder.
     *
     * <p>Default {@code BytesMarshallerFactory} is an instance of {@link
     * VanillaBytesMarshallerFactory}. This is a convenience configuration method, it has no effect
     * on the resulting hash containers, if {@linkplain #keyMarshaller(BytesMarshaller) custom data
     * marshallers} are configured, data types extends one of specific serialization interfaces,
     * recognized by this builder (e. g. {@code Externalizable} or {@code BytesMarshallable}), or
     * {@code ObjectSerializer} is configured.
     *
     * @param bytesMarshallerFactory the marshaller factory to be used with the default {@code
     *                               ObjectSerializer}, i. e. {@code BytesMarshallableSerializer}
     * @return this builder back
     * @see #objectSerializer(ObjectSerializer)
     */
    B bytesMarshallerFactory(BytesMarshallerFactory bytesMarshallerFactory);

    /**
     * Configures the serializer used to serialize/deserialize data to/from off-heap memory, when
     * specified class doesn't implement a specific serialization interface like {@link
     * Externalizable} or {@link BytesMarshallable} (for example, if data is loosely typed and just
     * {@code Object} is specified as the data class), or nullable data, and if custom marshaller is
     * not {@linkplain #keyMarshaller(BytesMarshaller) configured}, in hash containers, created by
     * this builder. Please read {@link ObjectSerializer} docs for more info and available options.
     *
     * <p>Default serializer is {@link BytesMarshallableSerializer}, configured with the specified
     * or default {@link #bytesMarshallerFactory(BytesMarshallerFactory) BytesMarshallerFactory}.
     *
     * @param objectSerializer the serializer used to serialize loosely typed or nullable data if
     *                         custom marshaller is not configured
     * @return this builder back
     * @see #bytesMarshallerFactory(BytesMarshallerFactory)
     * @see #keyMarshaller(BytesMarshaller)
     */
    B objectSerializer(ObjectSerializer objectSerializer);

    /**
     * Configures the {@code BytesMarshaller} used to serialize/deserialize keys to/from off-heap
     * memory in hash containers, created by this builder. See <a href="https://github.com/OpenHFT/Chronicle-Map#serialization">the
     * section about serialization in ChronicleMap manual</a> for more information.
     *
     * @param keyMarshaller the marshaller used to serialize keys
     * @return this builder back
     * @see #keyMarshallers(BytesWriter, BytesReader)
     * @see #objectSerializer(ObjectSerializer)
     */
    B keyMarshaller(@NotNull BytesMarshaller<? super K> keyMarshaller);

    /**
     * Configures the marshallers, used to serialize/deserialize keys to/from off-heap memory in
     * hash containers, created by this builder. See <a href="https://github.com/OpenHFT/Chronicle-Map#serialization">the
     * section about serialization in ChronicleMap manual</a> for more information.
     *
     * <p>Configuring marshalling this way results to a little bit more compact in-memory layout of
     * the map, comparing to a single interface configuration: {@link #keyMarshaller(BytesMarshaller)}.
     *
     * <p>Passing {@link BytesInterop} (which is a subinterface of {@link BytesWriter}) as the first
     * argument is supported, and even more advantageous from performance perspective.
     *
     * @param keyWriter the new key object &rarr; {@link Bytes} writer (interop) strategy
     * @param keyReader the new {@link Bytes} &rarr; key object reader strategy
     * @return this builder back
     * @see #keyMarshaller(BytesMarshaller)
     */
    B keyMarshallers(@NotNull BytesWriter<? super K> keyWriter, @NotNull BytesReader<K> keyReader);

    /**
     * Configures the marshaller used to serialize actual key sizes to off-heap memory in hash
     * containers, created by this builder.
     *
     * <p>Default key size marshaller is so-called "stop bit encoding" marshalling. If {@linkplain
     * #constantKeySizeBySample(Object) constant key size} is configured, or defaulted if the key
     * type is always constant and {@code ChronicleHashBuilder} implementation knows about it, this
     * configuration takes no effect, because a special {@link SizeMarshaller} implementation, which
     * doesn't actually do any marshalling, and just returns the known constant size on {@link
     * SizeMarshaller#readSize(Bytes)} calls, is used instead of any {@code SizeMarshaller}
     * configured using this method.
     *
     * @param keySizeMarshaller the new marshaller, used to serialize actual key sizes to off-heap
     *                          memory
     * @return this builder back
     */
    B keySizeMarshaller(@NotNull SizeMarshaller keySizeMarshaller);

    /**
     * Configures factory which is used to create a new key instance, if key class is either {@link
     * Byteable}, {@link BytesMarshallable} or {@link Externalizable} subclass, or key type is
     * eligible for data value generation, or {@linkplain #keyMarshallers(BytesWriter, BytesReader)
     * configured custom key reader} implements {@link DeserializationFactoryConfigurableBytesReader
     * }, in maps, created by this builder.
     *
     * <p>Default key deserialization factory is {@link NewInstanceObjectFactory}, which creates a
     * new key instance using {@link Class#newInstance()} default constructor. You could provide an
     * {@link AllocateInstanceObjectFactory}, which uses {@code Unsafe.allocateInstance(Class)} (you
     * might want to do this for better performance or if you don't want to initialize fields), or a
     * factory which calls a key class constructor with some arguments, or a factory which
     * internally delegates to instance pool or {@link ThreadLocal}, to reduce allocations.
     *
     * @param keyDeserializationFactory the key factory used to produce instances to deserialize
     *                                  data in
     * @return this builder back
     * @throws IllegalStateException if it is not possible to apply deserialization factory to
     *                               key deserializers, currently configured for this builder
     */
    B keyDeserializationFactory(@NotNull ObjectFactory<? extends K> keyDeserializationFactory);

    /**
     * Specifies that key objects, queried with the hash containers, created by this builder, are
     * inherently immutable. Keys in {@link ChronicleMap} or {@link ChronicleSet} are not required
     * to be immutable, as in ordinary {@link Map} or {@link Set} implementations, because they are
     * serialized off-heap. However, {@code ChronicleMap} and {@code ChronicleSet} implementations
     * can benefit from the knowledge that keys are not mutated between queries.
     *
     * <p>By default, {@code ChronicleHashBuilder}s detects immutability automatically only for very
     * few standard JDK types (for example, for {@link String}), it is not recommended to rely on
     * {@code ChronicleHashBuilder} to be smart enough about this.
     *
     * @return this builder back
     */
    B immutableKeys();

    /**
     * Specifies whether on the current combination of platform, OS and Jvm aligned 8-byte reads
     * and writes are atomic or not. By default, Chronicle tries to determine this itself, but
     * if it fails it pessimistically assumes that 64-bit memory operations are <i>not</i> atomic.
     *
     * @param aligned64BitMemoryOperationsAtomic {@code true} if aligned 8-byte memory operations
     * are atomic
     * @return this builder back
     */
    B aligned64BitMemoryOperationsAtomic(boolean aligned64BitMemoryOperationsAtomic);

    /**
     * Configures whether hash containers, created by this builder, should compute and store entry
     * checksums. It could be used to detect data corruption during recovery after crashes.
     *
     * <p>By default, {@linkplain #createPersistedTo(File) persisted} hash containers, created by
     * {@code ChronicleMapBuilder} <i>do</i> compute and store entry checksums, but hash containers,
     * created in the process memory via {@link #create()} - don't.
     *
     * @param checksumEntries if entry checksums should be computed and stored
     * @return this builder back
     * @see ChecksumEntry
     */
    B checksumEntries(boolean checksumEntries);

    /**
     * Configures replication of the hash containers, created by this builder. See <a
     * href="https://github.com/OpenHFT/Chronicle-Map#tcp--udp-replication"> the section about
     * replication in ChronicleMap manual</a> for more information.
     *
     * <p>By default, hash containers, created by this builder doesn't replicate their data.
     *
     * <p>This method call overrides all previous replication configurations of this builder, made
     * either by this method or {@link #replication(byte, TcpTransportAndNetworkConfig)} shortcut
     * method.
     *
     * @param replication the replication config
     * @return this builder back
     * @see ChronicleHashInstanceBuilder#replicated(SingleChronicleHashReplication)
     * @see #replication(byte, TcpTransportAndNetworkConfig)
     */
    B replication(SingleChronicleHashReplication replication);

    /**
     * Shortcut for {@code replication(SimpleReplication.builder() .tcpTransportAndNetwork(tcpTransportAndNetwork).createWithId(identifier))}.
     *
     * @param identifier             the network-wide identifier of the containers, created by this
     *                               builder
     * @param tcpTransportAndNetwork configuration of tcp connection and network
     * @return this builder back
     * @see #replication(SingleChronicleHashReplication)
     * @see ChronicleHashInstanceBuilder#replicated(byte, TcpTransportAndNetworkConfig)
     */
    B replication(byte identifier, TcpTransportAndNetworkConfig tcpTransportAndNetwork);

    B replication(byte identifier);

    ChronicleHashInstanceBuilder<H> instance();

    /**
     * Creates a new hash container, storing it's data in off-heap memory, not mapped to any file.
     * On {@link ChronicleHash#close()} called on the returned container, or after the container
     * object is collected during GC, or on JVM shutdown the off-heap memory used by the returned
     * container is freed.
     *
     * <p>This method is a shortcut for {@code instance().create()}.
     *
     * @return a new off-heap hash container
     * @see #createPersistedTo(File)
     * @see #instance()
     */
    H create();

    /**
     * Opens a hash container residing the specified file, or creates a new one if the file not yet
     * exists and maps its off-heap memory to the file. All changes to the map are persisted to disk
     * (this is an operating system guarantee) independently from JVM process lifecycle.
     *
     * <p>Multiple containers could give access to the same data simultaneously, either inside a
     * single JVM or across processes. Access is synchronized correctly across all instances, i. e.
     * hash container mapping the data from the first JVM isn't able to modify the data,
     * concurrently accessed from the second JVM by another hash container instance, mapping the
     * same data.
     *
     * <p>On container's {@link ChronicleHash#close() close()} the data isn't removed, it remains on
     * disk and available to be opened again (given the same file name) or during different JVM
     * run.
     *
     * <p>This method is shortcut for {@code instance().persistedTo(file).create()}.
     *
     * @param file the file with existing hash container or a desired location of a new off-heap
     *             persisted hash container
     * @return a hash container mapped to the given file
     * @throws IOException if any IO error, related to off-heap memory allocation or file mapping,
     *                     or establishing replication connections, occurs
     * @see ChronicleHash#file()
     * @see ChronicleHash#close()
     * @see #create()
     * @see ChronicleHashInstanceBuilder#persistedTo(File)
     */
    H createPersistedTo(File file) throws IOException;

    /**
     * @deprecated don't use private API in the client code
     */
    ChronicleHashBuilderPrivateAPI<K> privateAPI();
}
