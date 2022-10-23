package com.karolismed.simple_blockchain.blockchain;

import com.karolismed.simple_blockchain.blockchain.model.block.Block;
import com.karolismed.simple_blockchain.blockchain.model.User;
import com.karolismed.simple_blockchain.blockchain.model.transaction.Transaction;
import com.karolismed.simple_blockchain.blockchain.model.transaction.TransactionInput;
import com.karolismed.simple_blockchain.blockchain.model.transaction.TransactionOutput;
import com.karolismed.simple_blockchain.blockchain.model.transaction.UnspentOutput;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.HashSetValuedHashMap;
import org.apache.commons.lang3.tuple.Pair;

import static com.karolismed.simple_blockchain.constants.GlobalConstants.BLOCK_ZERO_HASH;
import static com.karolismed.simple_blockchain.constants.GlobalConstants.GENESIS_BLOCK_DIFFICULTY;
import static com.karolismed.simple_blockchain.constants.GlobalConstants.REGULAR_BLOCK_DIFFICULTY;
import static com.karolismed.simple_blockchain.constants.GlobalConstants.TRANSACTION_POOL_SIZE;
import static com.karolismed.simple_blockchain.utils.StringHelper.log;
import static java.util.Objects.isNull;

public class BlockChainRunner {

    private final BlockChainSetupService setupService;
    private final MerkleTreeConstructor merkleTreeConstructor;

    private List<User> users;
    private List<Block> blockChain;
    private List<Transaction> transactionPool;
    private List<Transaction> pendingTransactions;
    private MultiValuedMap<PublicKey, UnspentOutput> unspentOutputMap;
    private Map<String, Integer> txIdBlockIndexMap;
    private Map<PublicKey, User> pkUserMap;

    private int totalTransactionsPooled;

    public BlockChainRunner() {
        setupService = new BlockChainSetupService();
        merkleTreeConstructor = new MerkleTreeConstructor();

        blockChain = new LinkedList<>();
        users = new ArrayList<>();
        transactionPool = new LinkedList<>();
        pendingTransactions = new ArrayList<>();
        unspentOutputMap = new HashSetValuedHashMap<>();
        txIdBlockIndexMap = new HashMap<>();
        pkUserMap = new HashMap<>();
    }

    public void init() throws Exception {
        log("Setting up BlockChain...");
        setupService.setupUsers(users, pkUserMap);

        log("Mining genesis block...");
        mineGenesisBlock();

        log("Setting up miners...");
        BlockingQueue<Pair<Block, Integer>> blockQueue = new LinkedBlockingQueue<>();
        Semaphore threadSyncSemaphore = new Semaphore(11);
        Miner[] miners = {
            new Miner("Miner 1", transactionPool, 0, blockQueue, threadSyncSemaphore),
            new Miner("Miner 2", transactionPool, 1, blockQueue, threadSyncSemaphore),
            new Miner("Miner 3", transactionPool, 2, blockQueue, threadSyncSemaphore),
            new Miner("Miner 4", transactionPool, 3, blockQueue, threadSyncSemaphore),
            new Miner("Miner 5", transactionPool, 4, blockQueue, threadSyncSemaphore),
            new Miner("Miner 6", transactionPool, 5, blockQueue, threadSyncSemaphore),
            new Miner("Miner 7", transactionPool, 6, blockQueue, threadSyncSemaphore),
            new Miner("Miner 8", transactionPool, 7, blockQueue, threadSyncSemaphore),
            new Miner("Miner 9", transactionPool, 8, blockQueue, threadSyncSemaphore),
            new Miner("Miner 10", transactionPool, 9, blockQueue, threadSyncSemaphore),
            new Miner("Miner 11", transactionPool, 10, blockQueue, threadSyncSemaphore)
        };
        startMinerThreads(miners);


        while (true) {
            generatePendingTransactions();
            addPendingTransactionsToPool();

            if (transactionPool.isEmpty()) break;

            threadSyncSemaphore.acquire(miners.length);
            blockQueue.clear();
            startMiningNextBlock(blockChain.get(blockChain.size() - 1).getHash(), REGULAR_BLOCK_DIFFICULTY, miners);

            Pair<Block, Integer> minedValue = blockQueue.take();
            threadSyncSemaphore.acquire(miners.length);
            pauseAllMiners(miners);
            log(miners[minedValue.getRight()] + " mined block " + blockChain.size() + ": " + minedValue.getLeft());
            addBlockToBlockChain(minedValue.getLeft());
        }

        log("Block chain simulation ended");
        terminateAllMiners(miners);
    }

    private void startMinerThreads(Miner... miners) {
        for (Miner miner : miners) {
            new Thread(miner, miner.getName() + " thread").start();
        }
    }

    private void startMiningNextBlock(String prevBlockHash, int difficulty, Miner... miners) {
        for (Miner miner : miners) {
            miner.mineNextBlock(prevBlockHash, difficulty);
        }
    }

    private void pauseAllMiners(Miner... miners) {
        for (Miner miner : miners) {
            miner.pauseMining();
        }
    }

    private void terminateAllMiners(Miner... miners) {
        for (Miner miner : miners) {
            miner.stopExecution();
        }
    }

    private void mineGenesisBlock() throws InterruptedException {
        List<TransactionOutput> transactionOutputs = new ArrayList<>();

        users.forEach(user -> {
            transactionOutputs.add(
                TransactionOutput.builder().destPublicKey(user.getPublicKey())
                    .value(ThreadLocalRandom.current().nextInt(1, 1000000))
                    .build()
            );
        });

        transactionPool.add(new Transaction(new ArrayList<>(), transactionOutputs));

        BlockingQueue<Pair<Block, Integer>> genesisQueue = new LinkedBlockingQueue<>();
        Miner genesisMiner = new Miner("Genesis miner", transactionPool, 0, genesisQueue, new Semaphore(10));
        startMinerThreads(genesisMiner);
        startMiningNextBlock(BLOCK_ZERO_HASH, GENESIS_BLOCK_DIFFICULTY, genesisMiner);
        Block genesisBlock = genesisQueue.take().getKey();
        genesisMiner.stopExecution();

        addBlockToBlockChain(genesisBlock);
        log("mined genesis block " + genesisBlock);
    }

    private void addBlockToBlockChain(Block block) {
        String prevBlockHash = blockChain.isEmpty() ? BLOCK_ZERO_HASH : blockChain.get(blockChain.size() - 1).computeHash();
        if (!prevBlockHash.equals(block.getHeader().getPrevBlockHash())) {
            log("WARNING: Failed to add block to block chain, invalid prev block hash");
            return;
        }
        if (getCurrentTimestamp() - block.getHeader().getTimestamp() > 1000*60*60*2) {
            log("WARNING: Failed to add block to block chain, timestamp older than 2 hours");
            return;
        }
        if (!block.getHeader().getMerkleRootHash().equals(merkleTreeConstructor.getMerkleTreeRoot(block.getTransactions()))) {
            log("WARNING: Failed to add block to block chain, invalid merkle tree root");
            return;
        }

        int currentBlockDifficulty = blockChain.isEmpty() ? GENESIS_BLOCK_DIFFICULTY : REGULAR_BLOCK_DIFFICULTY;
        if (!block.computeHash().substring(0, currentBlockDifficulty).equals("0".repeat(currentBlockDifficulty))) {
            log("WARNING: Failed to add block to block chain, invalid block hash");
            return;
        }

        block.getTransactions().forEach(transaction -> {
            txIdBlockIndexMap.put(transaction.getTxId(), blockChain.size());
            for (int i = 0; i < transaction.getOutputs().size(); i++) {
                TransactionOutput output = transaction.getOutputs().get(i);
                unspentOutputMap.put(
                    output.getDestPublicKey(),
                    UnspentOutput.builder()
                        .destPublicKey(output.getDestPublicKey())
                        .txId(transaction.getTxId())
                        .outputIndex(i)
                        .build()
                );
            }
        });
        transactionPool.removeAll(block.getTransactions());

        blockChain.add(block);
    }

    private long getCurrentTimestamp() {
        return new Timestamp(System.currentTimeMillis()).getTime();
    }

    private void generatePendingTransactions() throws NoSuchAlgorithmException {
        if (totalTransactionsPooled >= TRANSACTION_POOL_SIZE) return;
        int userCount = users.size();

        Signature sign = Signature.getInstance("SHA256withDSA");

        unspentOutputMap.keySet().forEach(userKey -> {
            List<UnspentOutput> userUnspentOutputs = new ArrayList<>(unspentOutputMap.get(userKey));
            int balance = calculateUserBalance(userKey);
            if (balance < 2) return;

            int sumToSpend = ThreadLocalRandom.current().nextInt(1, balance);

            List<TransactionInput> inputs = new ArrayList<>();
            int sumAlreadySpend = 0;
            User currentUser = pkUserMap.get(userKey);

            Iterator<UnspentOutput> outputIterator = userUnspentOutputs.iterator();
            while (sumAlreadySpend < sumToSpend && outputIterator.hasNext()) {
                UnspentOutput output = outputIterator.next();
                sumAlreadySpend += getUnspentOutputValue(output);

                inputs.add(
                    TransactionInput.builder()
                        .index(output.getOutputIndex())
                        .txId(output.getTxId())
                        .signedTxId(currentUser.sign(sign, output.getTxId().getBytes()))
                        .build()
                );
            }

            List<TransactionOutput> outputs = new ArrayList<>();
            int destUserIndex = ThreadLocalRandom.current().nextInt(0, userCount);
            User destUser = users.get(destUserIndex);
            outputs.add(
                TransactionOutput.builder().value(sumToSpend).destPublicKey(destUser.getPublicKey()).build()
            );
            if (sumAlreadySpend > sumToSpend) {
                outputs.add(
                    TransactionOutput.builder().value(sumAlreadySpend - sumToSpend).destPublicKey(userKey).build()
                );
            }

            pendingTransactions.add(new Transaction(inputs, outputs));
        });
    }

    private void addPendingTransactionsToPool()
        throws NoSuchAlgorithmException, SignatureException, InvalidKeyException
    {
        Signature sign = Signature.getInstance("SHA256withDSA");

        for (Transaction pendingTransaction : pendingTransactions) {
            boolean isValid = true;
            List<Pair<PublicKey, UnspentOutput>> mappingsToRemove = new ArrayList<>();

            // Validate transaction here ↓
            int transactionInputValue = 0;

            if (!pendingTransaction.getTxId().equals(pendingTransaction.computeTransactionId())) {
                log(String.format(
                    "INFO: Invalid transaction %s: invalid transaction hash", pendingTransaction.getTxId()
                ));
                continue;
            }

            for (TransactionInput pendingInput : pendingTransaction.getInputs()) {
                String sourceTxId = pendingInput.getTxId();
                int sourceOutputIndex = pendingInput.getIndex();

                Transaction sourceTransaction = getTransactionFromBlockChainById(sourceTxId);
                TransactionOutput sourceOutput = sourceTransaction.getOutputs().get(sourceOutputIndex);

                PublicKey sourcePk = sourceOutput.getDestPublicKey();
                List<UnspentOutput> userUnspentOutputs = new ArrayList<>(unspentOutputMap.get(sourcePk));
                Optional<UnspentOutput> unspentOutput = userUnspentOutputs.stream()
                    .filter(output -> output.getTxId().equals(sourceTxId) && output.getOutputIndex() == sourceOutputIndex)
                    .findFirst();

                if (unspentOutput.isEmpty()) {
                    log(String.format(
                        "INFO: Invalid transaction %s: no unspent output present", pendingTransaction.getTxId()
                    ));
                    isValid = false;
                    break;
                }

                sign.initVerify(sourceOutput.getDestPublicKey());
                sign.update(sourceTransaction.getTxId().getBytes());
                if (!sign.verify(pendingInput.getSignedTxId())) {
                    log(String.format(
                        "INFO: Invalid transaction %s: Invalid digital signature", pendingTransaction.getTxId()
                    ));
                    isValid = false;
                    break;
                }

                mappingsToRemove.add(Pair.of(sourcePk, unspentOutput.get()));
                transactionInputValue += sourceOutput.getValue();
            }
            if (!isValid) continue;

            int transactionOutputValue = pendingTransaction.getOutputs()
                .stream()
                .mapToInt(TransactionOutput::getValue)
                .sum();
            if (transactionInputValue < transactionOutputValue) {
                log(String.format(
                    "INFO: Invalid transaction %s: More output that input", pendingTransaction.getTxId()
                ));
                isValid = false;
            }

            if (isValid) {
                mappingsToRemove.forEach(
                    mapping -> unspentOutputMap.removeMapping(mapping.getKey(), mapping.getValue())
                );
                transactionPool.add(pendingTransaction);
                totalTransactionsPooled++;
            }
        }

        pendingTransactions = new ArrayList<>();
    }

    private int getUnspentOutputValue(UnspentOutput output) {
        Transaction transaction = getTransactionFromBlockChainById(output.getTxId());
        if (isNull(transaction) || transaction.getOutputs().size() <= output.getOutputIndex()) {
            return 0;
        }
        return transaction.getOutputs().get(output.getOutputIndex()).getValue();
    }

    private int calculateUserBalance(PublicKey userPublicKey) {
        Collection<UnspentOutput> userUnspentOutputs = unspentOutputMap.get(userPublicKey);
        if (isNull(userUnspentOutputs) || userUnspentOutputs.isEmpty()) {
            return 0;
        }

        return userUnspentOutputs.stream()
            .mapToInt(this::getUnspentOutputValue)
            .sum();
    }

    private Transaction getTransactionFromBlockChainById(String txId) {
        Block block = blockChain.get(txIdBlockIndexMap.get(txId));
        return block.getTransactions().stream().filter(tx -> tx.getTxId().equals(txId))
            .findFirst().orElse(null);
    }
}
