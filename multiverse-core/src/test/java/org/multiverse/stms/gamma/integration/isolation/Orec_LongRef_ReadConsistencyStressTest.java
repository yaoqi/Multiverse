package org.multiverse.stms.gamma.integration.isolation;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.multiverse.TestThread;
import org.multiverse.api.Txn;
import org.multiverse.api.callables.TxnVoidCallable;
import org.multiverse.api.exceptions.ReadWriteConflict;
import org.multiverse.stms.gamma.*;
import org.multiverse.stms.gamma.GammaTxnExecutor;
import org.multiverse.stms.gamma.transactionalobjects.BaseGammaTxnRef;
import org.multiverse.stms.gamma.transactionalobjects.GammaTxnLong;
import org.multiverse.stms.gamma.transactionalobjects.Tranlocal;
import org.multiverse.stms.gamma.transactions.GammaTxn;
import org.multiverse.stms.gamma.transactions.GammaTxnConfig;
import org.multiverse.stms.gamma.transactions.fat.FatFixedLengthGammaTxn;
import org.multiverse.stms.gamma.transactions.fat.FatVariableLengthGammaTxn;
import org.multiverse.stms.gamma.transactions.fat.FatVariableLengthGammaTxnFactory;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.multiverse.TestUtils.*;

/**
 * Conclusion so far:
 * with refs it fails
 * with longs it succeeds.
 */
public class Orec_LongRef_ReadConsistencyStressTest implements GammaConstants {

    private GammaStm stm;
    private GammaTxnLong[] refs;
    private volatile boolean stop;
    private final AtomicBoolean inconstencyDetected = new AtomicBoolean();
    private int readingThreadCount;
    private int writingThreadCount;
    private int refCount;
    private int durationMs;

    @Before
    public void setUp() {
        stm = new GammaStm();
        stop = false;
        refCount = 128;
        readingThreadCount = 10;
        writingThreadCount = 2;
        durationMs = 300 * 1000;

        refs = new GammaTxnLong[refCount];
        for (int k = 0; k < refs.length; k++) {
            refs[k] = new GammaTxnLong(stm, 0);
        }
        inconstencyDetected.set(false);
    }

    @After
    public void after() {
        for (GammaTxnLong ref : refs) {
            System.out.println(ref.toDebugString());
        }
    }

    @Test
    public void test() {
        FatVariableLengthTransactionWithBlockThread[] readingThreads =
                new FatVariableLengthTransactionWithBlockThread[readingThreadCount];
        for (int k = 0; k < readingThreads.length; k++) {
            readingThreads[k] = new FatVariableLengthTransactionWithBlockThread(k);
        }

        UpdatingThread[] threads = new UpdatingThread[writingThreadCount];
        for (int k = 0; k < threads.length; k++) {
            threads[k] = new UpdatingThread(k);
        }

        startAll(readingThreads);
        startAll(threads);
        sleepMs(durationMs);
        stop = true;
        joinAll(readingThreads);
        joinAll(threads);
        assertFalse(inconstencyDetected.get());
    }

    class UpdatingThread extends TestThread {
        private int id;

        public UpdatingThread(int id) {
            super("UpdatingThread-" + id);
            this.id = id;
        }

        @Override
        public void doRun() throws Exception {
            GammaTxnExecutor executor = stm.newTxnFactoryBuilder()
                    .setSpeculative(false)
                    .setMaxRetries(100000)
                    .newTxnExecutor();
            TxnVoidCallable callable = new TxnVoidCallable() {
                @Override
                public void call(Txn tx) throws Exception {
                    for (GammaTxnLong ref : refs) {
                        ref.incrementAndGet(1);
                    }
                }
            };

            int iteration = 0;
            while (!stop) {
                executor.execute(callable);
                sleepRandomUs(100);
                iteration++;

                if (iteration % 100000 == 0) {
                    System.out.printf("%s is at %s\n", getName(), iteration);
                }
            }
        }
    }

    class ReadingThread extends TestThread {

        private Tranlocal[] tranlocals;
        private long lastConflictCount = stm.getGlobalConflictCounter().count();
        private GammaObjectPool pool = new GammaObjectPool();
        private GammaTxn dummyTx = stm.newDefaultTxn();

        public ReadingThread(int id) {
            super("ReadingThread-" + id);

            tranlocals = new Tranlocal[refs.length];
            for (int k = 0; k < tranlocals.length; k++) {
                Tranlocal tranlocal = new Tranlocal();
                tranlocal.owner = refs[k];
                tranlocals[k] = tranlocal;
            }
        }

        @Override
        public void doRun() throws Exception {
            long iteration = 0;
            while (!stop) {
                singleRun(System.nanoTime() % 10 == 0 && false);
                iteration++;

                if (iteration % 10000 == 0) {
                    System.out.printf("%s is at %s\n", getName(), iteration);
                }
            }
        }

        private void singleRun(boolean write) {
            boolean success = false;
            while (!success) {
                assertCorrectlyCleared();
                fullRead();
                assertReadConsistent();
                success = !write || fullWrite();
            }

            releaseChainAfterSuccess(write);
        }

        private boolean fullWrite() {
            for (int k = 0; k < refs.length; k++) {
                GammaTxnLong ref = refs[k];
                Tranlocal tranlocal = tranlocals[k];

                //if (!ref.tryLockAfterNormalArrive(64, LOCKMODE_EXCLUSIVE)) {
                //    releaseChainAfterFailure();
                //    return false;
                //}

                tranlocal.lockMode = LOCKMODE_EXCLUSIVE;

                if (tranlocal.version != ref.version) {
                    releaseChainAfterFailure();
                    return false;
                }
            }

            for (int k = 0; k < refs.length; k++) {
                refs[k].version++;
                refs[k].departAfterUpdateAndUnlock();
                tranlocals[k].lockMode = LOCKMODE_NONE;
                tranlocals[k].hasDepartObligation = false;
            }
            return true;
        }

        private void fullRead() {
            for (; ;) {
                lastConflictCount = stm.getGlobalConflictCounter().count();
                for (int k = 0; k < refs.length; k++) {
                    GammaTxnLong ref = refs[k];
                    Tranlocal tranlocal = tranlocals[k];
                    if (!ref.load(dummyTx,tranlocal, LOCKMODE_NONE, 64, true)) {
                        releaseChainAfterFailure();
                        break;
                    }

                    if (!isReadConsistent()) {
                        releaseChainAfterFailure();
                        break;
                    }

                    if (k == refs.length - 1) {
                        return;
                    }
                }
            }
        }

        private void assertReadConsistent() {
            long version = tranlocals[0].version;
            for (int k = 1; k < tranlocals.length; k++) {
                if (version != tranlocals[k].version) {
                    System.out.println("Inconsistency detected");
                    inconstencyDetected.compareAndSet(false, true);
                    stop = true;
                    break;
                }
            }
        }

        private boolean isReadConsistent() {
            long globalConflictCount = stm.getGlobalConflictCounter().count();

            if (lastConflictCount == globalConflictCount) {
                return true;
            }

            lastConflictCount = globalConflictCount;

            for (Tranlocal tranlocal : tranlocals) {
                BaseGammaTxnRef owner = tranlocal.owner;

                if (!tranlocal.hasDepartObligation) {
                    continue;
                }

                if (owner.hasExclusiveLock()) {
                    return false;
                }

                if (tranlocal.version != owner.version) {
                    return false;
                }
            }

            return true;
        }

        private void assertCorrectlyCleared() {
            for (Tranlocal tranlocal : tranlocals) {
                assertFalse(tranlocal.hasDepartObligation);
                assertEquals(LOCKMODE_NONE, tranlocal.lockMode);
            }
        }

        private void releaseChainAfterFailure() {
            for (Tranlocal tranlocal : tranlocals) {
                BaseGammaTxnRef owner = tranlocal.owner;
                tranlocal.owner.releaseAfterFailure(tranlocal, pool);
                tranlocal.owner = owner;
                //if (tranlocal.hasDepartObligation) {
                //    tranlocal.hasDepartObligation = false;
                //    if (tranlocal.lockMode == LOCKMODE_NONE) {
                //        tranlocal.owner.departAfterFailure();
                //    } else {
                //        tranlocal.lockMode = LOCKMODE_NONE;
                //        tranlocal.owner.departAfterFailureAndUnlock();
                //    }
                //}
            }
        }

        private void releaseChainAfterSuccess(boolean write) {
            for (Tranlocal tranlocal : tranlocals) {
                BaseGammaTxnRef owner = tranlocal.owner;
                if (write) {
                    owner.releaseAfterUpdate(tranlocal, pool);
                } else {
                    owner.releaseAfterReading(tranlocal, pool);
                }
                tranlocal.owner = owner;
            }
        }
    }

    class FixedReadingThread extends TestThread {

        private FatFixedLengthGammaTxn tx = new FatFixedLengthGammaTxn(
                new GammaTxnConfig(stm, refs.length)
                        .setMaximumPoorMansConflictScanLength(0)
                        .setDirtyCheckEnabled(false)
        );

        public FixedReadingThread(int id) {
            super("ReadingThread-" + id);
        }

        @Override
        public void doRun() throws Exception {
            long iteration = 0;
            while (!stop) {
                singleRun(System.nanoTime() % 10 == 0 && false);
                iteration++;

                if (iteration % 1000 == 0) {
                    System.out.printf("%s is at %s\n", getName(), iteration);
                }
            }
        }

        private void singleRun(boolean write) {
            tx.hardReset();
            boolean success = false;
            while (!success) {
                fullRead(write);
                assertReadConsistent(tx);
                try {
                    tx.commit();
                    success = true;
                } catch (ReadWriteConflict expected) {
                    success = false;
                    tx.attempt = 1;
                    tx.softReset();
                }
            }

            tx.commit();
        }

        private void fullRead(boolean write) {
            for (; ;) {
                try {
                    for (int k = 0; k < refs.length; k++) {
                        if (write) {
                            refs[k].openForWrite(tx, LOCKMODE_NONE);
                        } else {
                            Tranlocal tranlocal = refs[k].openForRead(tx, LOCKMODE_NONE);
                            tranlocal.long_value = tranlocal.version + 1;
                        }
                        if (k == refs.length - 1) {
                            return;
                        }
                    }
                } catch (ReadWriteConflict expected) {
                    tx.attempt = 1;
                    tx.softReset();
                }
            }

        }

    }

    class VariableReadingThread extends TestThread {

        private FatVariableLengthGammaTxn tx = new FatVariableLengthGammaTxn(
                new GammaTxnConfig(stm, refs.length)
                        .setMaximumPoorMansConflictScanLength(0)
                        .setDirtyCheckEnabled(false)
        );

        public VariableReadingThread(int id) {
            super("ReadingThread-" + id);
        }

        @Override
        public void doRun() throws Exception {
            long iteration = 0;
            while (!stop) {
                singleRun(System.nanoTime() % 10 == 0 && false);
                iteration++;

                if (iteration % 1000 == 0) {
                    System.out.printf("%s is at %s\n", getName(), iteration);
                }
            }
        }

        private void singleRun(boolean write) {
            tx.hardReset();
            boolean success = false;
            while (!success) {
                fullRead(write);
                assertReadConsistent(tx);
                try {
                    tx.commit();
                    success = true;
                } catch (ReadWriteConflict expected) {
                    success = false;
                    tx.attempt = 1;
                    tx.softReset();
                }
            }

            tx.commit();
        }

        private void fullRead(boolean write) {
            for (; ;) {
                try {
                    for (int k = 0; k < refs.length; k++) {
                        if (write) {
                            Tranlocal tranlocal = refs[k].openForWrite(tx, LOCKMODE_NONE);
                            tranlocal.ref_value = getName();
                        } else {
                            refs[k].openForRead(tx, LOCKMODE_NONE);
                        }

                        if (k == refs.length - 1) {
                            return;
                        }
                    }
                } catch (ReadWriteConflict expected) {
                    tx.attempt = 1;
                    tx.softReset();
                }
            }
        }
    }

    class FatVariableLengthTransactionWithBlockThread extends TestThread {

        private LeanGammaTxnExecutor executor;

        public FatVariableLengthTransactionWithBlockThread(int id) {
            super("VariableReadingWithBlockThread-" + id);
        }

        @Override
        public void doRun() throws Exception {
            GammaTxnConfig config = new GammaTxnConfig(stm, refs.length)
                    .setMaximumPoorMansConflictScanLength(0)
                    .setMaxRetries(100000)
                    .setSpeculative(false)
                    .setDirtyCheckEnabled(false);

            executor = new LeanGammaTxnExecutor(new FatVariableLengthGammaTxnFactory(config));

            long iteration = 0;
            while (!stop) {
                singleRun();
                iteration++;

                if (iteration % 1000 == 0) {
                    System.out.printf("%s is at %s\n", getName(), iteration);
                }
            }
        }

        private void singleRun() {
            executor.execute(new TxnVoidCallable() {
                @Override
                public void call(Txn tx) throws Exception {
                    fullRead((GammaTxn) tx);
                    assertReadConsistent((GammaTxn) tx);
                }
            });
        }

        private void fullRead(GammaTxn tx) {
            long value = refs[0].get(tx);

            for (int k = 0; k < refs.length; k++) {
                assertEquals(value, refs[k].openForWrite(tx, LOCKMODE_NONE).long_value);
            }
        }
    }

    private void assertReadConsistent(GammaTxn tx) {
        long version = tx.getRefTranlocal(refs[0]).version;
        long value = refs[0].get(tx);
        for (int k = 1; k < refs.length; k++) {
            boolean b = version == tx.getRefTranlocal(refs[k]).version
                    && value == refs[k].get(tx);

            if (!b) {
                System.out.println("Inconsistency detected");
                inconstencyDetected.compareAndSet(false, true);
                stop = true;
                break;
            }
        }
    }
}
