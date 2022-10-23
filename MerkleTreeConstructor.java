package com.karolismed.simple_blockchain.blockchain;

import com.karolismed.simple_blockchain.blockchain.model.transaction.Transaction;
import com.karolismed.simple_blockchain.hashing.HashingService;
import lombok.NonNull;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.stream.Collectors;

public class MerkleTreeConstructor {

    private HashingService hashingService;

    public MerkleTreeConstructor() {
        this.hashingService = new HashingService();
    }

    public String getMerkleTreeRoot(@NonNull List<Transaction> transactions) {
        return getMerkleTreeRootFromIds(
            transactions.stream().map(Transaction::getTxId).collect(Collectors.toList())
        );
    }

    public String getMerkleTreeRootFromIds(@NonNull List<String> transactionHashes) {
        List<Sha256Hash> reversedHashes = transactionHashes.stream()
            .map(hash -> Sha256Hash.wrapReversed(Utils.HEX.decode(hash.toLowerCase())))
            .collect(Collectors.toList());
        return Utils.HEX.encode(computeMerkleTreeRoot(reversedHashes).getReversedBytes());
    }

    private Sha256Hash computeMerkleTreeRoot(@NonNull List<Sha256Hash> merkle) {
        // Stop if hash list is empty or contains one element
        if (merkle.isEmpty()) {
            return Sha256Hash.ZERO_HASH;
        }
        else if (merkle.size() == 1) {
            return merkle.get(0);
        }


        // While there is more than 1 hash in the list, keep looping...
        while (merkle.size() > 1) {
            // If number of hashes is odd, duplicate last hash in the list.
            if (merkle.size() % 2 != 0) {
                merkle.add(merkle.get(merkle.size() - 1));
            }
            assert merkle.size() % 2 == 0;

            // New hash list.
            List<Sha256Hash> newMerkle = new ArrayList<>();
            // Loop through hashes 2 at a time.
            for (ListIterator<Sha256Hash> it = merkle.listIterator(); it.hasNext();) {
                byte[] concatData = new byte[Sha256Hash.LENGTH * 2];
                System.arraycopy(it.next().getBytes(), 0, concatData, 0, Sha256Hash.LENGTH);
                System.arraycopy(it.next().getBytes(), 0, concatData, Sha256Hash.LENGTH, Sha256Hash.LENGTH);

                // Hash both of the hashes.
                Sha256Hash newRoot = Sha256Hash.wrap(Sha256Hash.hashTwice(concatData));
                // Add this to the new list.
                newMerkle.add(newRoot);
            }
            // This is the new list.
            merkle = newMerkle;
        }
        // Finally we end up with a single item.
        return merkle.get(0);
    }

    public String getMerkleTreeRootOld(@NonNull List<Transaction> transactions) {
        List<String> currentLevel =
            transactions.stream().map(Transaction::getTxId).collect(Collectors.toList());

        if (currentLevel.isEmpty()) {
            return hashingService.hash("");
        }

        currentLevel = currentLevel.stream()
            .map(txId -> hashingService.hash(txId))
            .collect(Collectors.toList());

        while (currentLevel.size() > 1) {
            List<String> temp = new ArrayList<>();
            if (currentLevel.size() % 2 != 0) {
                currentLevel.add(currentLevel.get(currentLevel.size() - 1));
            }

            for (int i = 0; i < currentLevel.size(); i += 2) {
                temp.add(hashingService.hash(currentLevel.get(i) + currentLevel.get(i + 1)));
            }
            currentLevel = temp;
        }

        return currentLevel.get(0);
    }
}
