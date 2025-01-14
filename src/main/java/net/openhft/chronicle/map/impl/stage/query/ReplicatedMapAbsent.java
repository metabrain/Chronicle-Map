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

package net.openhft.chronicle.map.impl.stage.query;

import net.openhft.chronicle.hash.Data;
import net.openhft.chronicle.hash.impl.stage.query.HashQuery.EntryPresence;
import net.openhft.chronicle.map.impl.stage.entry.ReplicatedMapEntryStages;
import net.openhft.chronicle.map.impl.stage.replication.ReplicationUpdate;
import net.openhft.sg.StageRef;
import net.openhft.sg.Staged;

import static net.openhft.chronicle.hash.impl.stage.query.KeySearch.SearchState.PRESENT;

@Staged
public abstract class ReplicatedMapAbsent<K, V> extends MapAbsent<K, V> {

    @StageRef MapQuery<K, V, ?> q;
    @StageRef ReplicatedMapEntryStages<K, V> e;
    @StageRef ReplicationUpdate<K> ru;

    @Override
    public void doInsert(Data<V> value) {
        q.putPrefix();
        if (!q.entryPresent()) {
            if (!ks.searchStatePresent()) {
                putEntry(value);
                ks.setSearchState(PRESENT);
                q.initPresenceOfEntry(EntryPresence.PRESENT);
            } else {
                e.innerDefaultReplaceValue(value);
                s.deleted(s.deleted() - 1);
            }
            s.incrementModCount();
            e.writeEntryPresent();
            ru.updateChange();
            e.updatedReplicationStateOnAbsentEntry();
            e.checksumStrategy.computeAndStoreChecksum();
        } else {
            throw new IllegalStateException("Entry is absent in the map when doInsert() is called");
        }
    }
}
