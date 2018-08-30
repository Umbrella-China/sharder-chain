/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.conch.vm.db;


import com.sun.istack.internal.Nullable;
import org.conch.Block;
import org.conch.Db;
import org.conch.util.Logger;
import org.conch.vm.DataWord;
import org.conch.vm.crypto.HashUtil;
import org.conch.vm.util.ByteArrayWrapper;
import org.conch.vm.util.ByteUtil;
import org.conch.vm.util.FastByteComparisons;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.*;

/**
 * Created by Anton Nashatyrev on 07.10.2016.
 */
public class RepositoryImpl implements Repository {

    protected RepositoryImpl parent;

    protected HashMap<String, AccountState> accountStateCache;
    protected HashMap<String, byte[]> codeCache;
    protected HashMap<String, HashMap<DataWord, DataWord>> storageCache;


    public RepositoryImpl() {
        this.accountStateCache = new HashMap<>();
        this.codeCache = new HashMap<>();
        this.storageCache = new HashMap<>();
    }

    public RepositoryImpl(HashMap<String, AccountState> accountStateCache, HashMap<String, byte[]> codeCache,
                          HashMap<String, HashMap<DataWord, DataWord>> storageCache) {
        init(accountStateCache, codeCache, storageCache);
    }

    protected void init(HashMap<String, AccountState> accountStateCache, HashMap<String, byte[]> codeCache,
                        HashMap<String, HashMap<DataWord, DataWord>> storageCache) {
        this.accountStateCache = accountStateCache;
        this.codeCache = codeCache;
        this.storageCache = storageCache;
    }

    @Override
    public synchronized AccountState createAccount(byte[] addr) {
        AccountState state = new AccountState(BigInteger.ZERO,
                BigInteger.ZERO);
        accountStateCache.put(ByteUtil.toHexString(addr), state);
        return state;
    }

    @Override
    public synchronized boolean isExist(byte[] addr) {
        return getAccountState(addr) != null;
    }

    @Override
    public synchronized AccountState getAccountState(byte[] addr) {
        if (accountStateCache.containsKey(ByteUtil.toHexString(addr)))
            return accountStateCache.get(ByteUtil.toHexString(addr));
        else {
            return Source.getAccountState(ByteUtil.toHexString(addr));
        }
    }

    synchronized AccountState getOrCreateAccountState(byte[] addr) {
        AccountState ret = getAccountState(addr);
        if (ret == null) {
            ret = createAccount(addr);
        }
        return ret;
    }

    @Override
    public synchronized void delete(byte[] addr) {
        accountStateCache.remove(ByteUtil.toHexString(addr));
        storageCache.remove(ByteUtil.toHexString(addr));
    }

    @Override
    public synchronized BigInteger increaseNonce(byte[] addr) {
        AccountState accountState = getOrCreateAccountState(addr);
        accountStateCache.put(ByteUtil.toHexString(addr), accountState.withIncrementedNonce());
        return accountState.getNonce();
    }

    @Override
    public synchronized BigInteger setNonce(byte[] addr, BigInteger nonce) {
        AccountState accountState = getOrCreateAccountState(addr);
        accountStateCache.put(ByteUtil.toHexString(addr), accountState.withNonce(nonce));
        return accountState.getNonce();
    }

    @Override
    public synchronized BigInteger getNonce(byte[] addr) {
        AccountState accountState = getAccountState(addr);
        return accountState == null ? BigInteger.ZERO :
                accountState.getNonce();
    }

    @Override
    public synchronized ContractDetails getContractDetails(byte[] addr) {
        return new ContractDetailsImpl(addr);
    }

    @Override
    public synchronized boolean hasContractDetails(byte[] addr) {
        return getContractDetails(addr) != null;
    }

    @Override
    public synchronized void saveCode(byte[] addr, byte[] code) {
        byte[] codeHash = HashUtil.sha3(code);
        codeCache.put(ByteUtil.toHexString(codeKey(codeHash, addr)), code);
        AccountState accountState = getOrCreateAccountState(addr);
        accountStateCache.put(ByteUtil.toHexString(addr), accountState.withCodeHash(codeHash));
    }

    @Override
    public synchronized byte[] getCode(byte[] addr) {
        byte[] codeHash = getCodeHash(addr);
        if (FastByteComparisons.equal(codeHash, HashUtil.EMPTY_DATA_HASH))
            return ByteUtil.EMPTY_BYTE_ARRAY;
        else {
            String codeAddr = ByteUtil.toHexString(codeKey(codeHash, addr));
            if (codeCache.containsKey(codeAddr))
                return codeCache.get(codeAddr);
            else
                return Source.getBytesValue(codeAddr);
        }
    }

    // composing a key as there can be several contracts with the same code
    private byte[] codeKey(byte[] codeHash, byte[] addr) {
        return NodeKeyCompositor.compose(codeHash, addr);
    }

    @Override
    public byte[] getCodeHash(byte[] addr) {
        AccountState accountState = getAccountState(addr);
        return accountState != null ? accountState.getCodeHash() : HashUtil.EMPTY_DATA_HASH;
    }

    @Override
    public synchronized void addStorageRow(byte[] addr, DataWord key, DataWord value) {
        getOrCreateAccountState(addr);

        getStorageValue(addr, key);
        if (!storageCache.containsKey(ByteUtil.toHexString(addr))) {
            storageCache.put(ByteUtil.toHexString(addr), new HashMap<>());
        }

        HashMap<DataWord, DataWord> contractStorage = storageCache.get(ByteUtil.toHexString(addr));
        contractStorage.put(key, value.isZero() ? null : value);
    }

    @Override
    public synchronized DataWord getStorageValue(byte[] addr, DataWord key) {
        AccountState accountState = getAccountState(addr);
        if (accountState == null)
            return null;
        if (!storageCache.containsKey(ByteUtil.toHexString(addr)) ||
                storageCache.get(ByteUtil.toHexString(addr)).containsKey(ByteUtil.toHexString(addr) + key.toString())) {
            DataWord storage = Source.getStorage(addr, key);
            if (storage == null)
                return null;
            else if (storageCache.containsKey(ByteUtil.toHexString(addr)))
                storageCache.get(ByteUtil.toHexString(addr)).put(key, storage);
            else {
                HashMap<DataWord, DataWord> map = new HashMap<>();
                map.put(key, storage);
                storageCache.put(ByteUtil.toHexString(addr), map);
            }
        }
        return storageCache.get(ByteUtil.toHexString(addr)).get(key);
    }

    @Override
    public synchronized BigInteger getBalance(byte[] addr) {
        AccountState accountState = getAccountState(addr);
        return accountState == null ? BigInteger.ZERO : accountState.getBalance();
    }

    @Override
    public synchronized BigInteger addBalance(byte[] addr, BigInteger value) {
        AccountState accountState = getOrCreateAccountState(addr);
        accountStateCache.put(ByteUtil.toHexString(addr), accountState.withBalanceIncrement(value));
        return accountState.getBalance();
    }

    @Override
    public synchronized RepositoryImpl startTracking() {
        HashMap<String, AccountState> trackAccountStateCache = new HashMap<>();
        trackAccountStateCache.putAll(accountStateCache);
        HashMap<String, byte[]> trackCodeCache = new HashMap<>();
        trackCodeCache.putAll(codeCache);
        HashMap<String, HashMap<DataWord, DataWord>> trackStorageCache = new HashMap<>();
        trackStorageCache.putAll(storageCache);

        RepositoryImpl ret = new RepositoryImpl(trackAccountStateCache, trackCodeCache, trackStorageCache);
        ret.parent = this;
        return ret;
    }

    @Override
    public synchronized Repository getSnapshotTo(byte[] root) {
        return parent.getSnapshotTo(root);
    }

    @Override
    public synchronized void commit() {
        Repository parentSync = parent == null ? this : parent;
        // need to synchronize on parent since between different caches flush
        // the parent repo would not be in consistent state
        // when no parent just take this instance as a mock
        synchronized (parentSync) {
            Connection connection = null;
            Savepoint savepoint = null;
            try {
                // TODO wj should be delete ,because the block already has transaction
                connection = Db.db.getConnection();
                savepoint = connection.setSavepoint();
                Source source = new Source();
                source.flushStorage(storageCache);
                source.flushCodeCache(codeCache);
                source.flushAccountState(accountStateCache);
                connection.commit();
            } catch (SQLException e) {
                try {
                    if (connection == null || savepoint == null) {
                        Logger.logErrorMessage("save contract data to database error : ", e);
                        return;
                    }
                    connection.rollback(savepoint);
                } catch (SQLException e1) {
                    Logger.logErrorMessage("Contract roll back error ", e1);
                }
            }
        }
    }

    @Override
    public synchronized void rollback() {
        // nothing to do, will be GCed
    }

    @Override
    public byte[] getRoot() {
        throw new RuntimeException("Not supported");
    }

    public synchronized String getTrieDump() {
        return dumpStateTrie();
    }

    public String dumpStateTrie() {
        throw new RuntimeException("Not supported");
    }

    class ContractDetailsImpl implements ContractDetails {
        private byte[] address;

        public ContractDetailsImpl(byte[] address) {
            this.address = address;
        }

        @Override
        public void put(DataWord key, DataWord value) {
            RepositoryImpl.this.addStorageRow(address, key, value);
        }

        @Override
        public DataWord get(DataWord key) {
            return RepositoryImpl.this.getStorageValue(address, key);
        }

        @Override
        public byte[] getCode() {
            return RepositoryImpl.this.getCode(address);
        }

        @Override
        public byte[] getCode(byte[] codeHash) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void setCode(byte[] code) {
            RepositoryImpl.this.saveCode(address, code);
        }

        @Override
        public byte[] getStorageHash() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void decode(byte[] rlpCode) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void setDirty(boolean dirty) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void setDeleted(boolean deleted) {
            RepositoryImpl.this.delete(address);
        }

        @Override
        public boolean isDirty() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public boolean isDeleted() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public byte[] getEncoded() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public int getStorageSize() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public Set<DataWord> getStorageKeys() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public Map<DataWord, DataWord> getStorage(@Nullable Collection<DataWord> keys) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public Map<DataWord, DataWord> getStorage() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void setStorage(List<DataWord> storageKeys, List<DataWord> storageValues) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void setStorage(Map<DataWord, DataWord> storage) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public byte[] getAddress() {
            return address;
        }

        @Override
        public void setAddress(byte[] address) {
            throw new RuntimeException("Not supported");
        }

        @Override
        public ContractDetails clone() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public void syncStorage() {
            throw new RuntimeException("Not supported");
        }

        @Override
        public ContractDetails getSnapshotTo(byte[] hash) {
            throw new RuntimeException("Not supported");
        }
    }


    @Override
    public Set<byte[]> getAccountsKeys() {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void dumpState(Block block, long gasUsed, int txNumber, byte[] txHash) {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void flush() {
        throw new RuntimeException("Not supported");
    }


    @Override
    public void flushNoReconnect() {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void syncToRoot(byte[] root) {
        throw new RuntimeException("Not supported");
    }

    @Override
    public boolean isClosed() {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void close() {
    }

    @Override
    public void reset() {
        throw new RuntimeException("Not supported");
    }

    @Override
    public void updateBatch(HashMap<ByteArrayWrapper, AccountState> accountStates, HashMap<ByteArrayWrapper, ContractDetails> contractDetailes) {
        for (Map.Entry<ByteArrayWrapper, AccountState> entry : accountStates.entrySet()) {
            accountStateCache.put(ByteUtil.toHexString(entry.getKey().getData()), entry.getValue());
        }
        for (Map.Entry<ByteArrayWrapper, ContractDetails> entry : contractDetailes.entrySet()) {
            ContractDetails details = getContractDetails(entry.getKey().getData());
            for (DataWord key : entry.getValue().getStorageKeys()) {
                details.put(key, entry.getValue().get(key));
            }
            byte[] code = entry.getValue().getCode();
            if (code != null && code.length > 0) {
                details.setCode(code);
            }
        }
    }

    @Override
    public void loadAccount(byte[] addr, HashMap<ByteArrayWrapper, AccountState> cacheAccounts, HashMap<ByteArrayWrapper, ContractDetails> cacheDetails) {
        throw new RuntimeException("Not supported");
    }

}