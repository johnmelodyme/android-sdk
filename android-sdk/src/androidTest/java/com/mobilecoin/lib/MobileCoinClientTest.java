// Copyright (c) 2020-2021 MobileCoin. All rights reserved.

package com.mobilecoin.lib;

import android.Manifest;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.mobilecoin.lib.exceptions.AmountDecoderException;
import com.mobilecoin.lib.exceptions.AttestationException;
import com.mobilecoin.lib.exceptions.FeeRejectedException;
import com.mobilecoin.lib.exceptions.FogReportException;
import com.mobilecoin.lib.exceptions.FragmentedAccountException;
import com.mobilecoin.lib.exceptions.InsufficientFundsException;
import com.mobilecoin.lib.exceptions.InvalidFogResponse;
import com.mobilecoin.lib.exceptions.InvalidReceiptException;
import com.mobilecoin.lib.exceptions.InvalidTransactionException;
import com.mobilecoin.lib.exceptions.InvalidUriException;
import com.mobilecoin.lib.exceptions.NetworkException;
import com.mobilecoin.lib.exceptions.SerializationException;
import com.mobilecoin.lib.exceptions.TransactionBuilderException;
import com.mobilecoin.lib.log.Logger;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static com.mobilecoin.lib.Environment.TEST_PASSWORD;
import static com.mobilecoin.lib.Environment.TEST_USERNAME;
import static com.mobilecoin.lib.UtilTest.waitForReceiptStatus;
import static com.mobilecoin.lib.UtilTest.waitForTransactionStatus;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */

@RunWith(AndroidJUnit4.class)
public class MobileCoinClientTest {
    private static final String TAG = MobileCoinClient.class.toString();

    @Rule
    public GrantPermissionRule mRuntimePermissionRule =
            GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE);

    @Test
    public void test_balance_consistency()
            throws InvalidFogResponse, NetworkException, AttestationException, InvalidUriException {
        MobileCoinClient mobileCoinClient = Environment.makeFreshMobileCoinClient();
        try {
            Balance balance1 = mobileCoinClient.getBalance();
            Logger.d(
                    TAG,
                    "Balance 1: " + balance1.toString()
            );
            Balance balance2 = mobileCoinClient.getBalance();
            Logger.d(
                    TAG,
                    "Balance 2: " + balance2.toString()
            );
            Assert.assertEquals(
                    balance1,
                    balance2
            );
        } finally {
            mobileCoinClient.shutdown();
        }
    }

    @Test
    public void test_balance_retrieval()
            throws InvalidFogResponse, NetworkException, AttestationException, InvalidUriException {
        MobileCoinClient mobileCoinClient = Environment.makeFreshMobileCoinClient();
        Balance balance = mobileCoinClient.getBalance();
        Logger.d(
                TAG,
                "Balance: " + balance.toString()
        );
        Assert.assertTrue(
                "Expect non-zero balance",
                balance.getAmountPicoMob().compareTo(BigInteger.ZERO) > 0
        );
        mobileCoinClient.shutdown();
    }

    @Test
    public void test_balance_updates_correctly()
            throws InvalidTransactionException, FragmentedAccountException,
            InsufficientFundsException, InvalidFogResponse, FeeRejectedException,
            AttestationException, NetworkException,
            TransactionBuilderException, FogReportException, TimeoutException,
            InterruptedException, InvalidUriException {

        BigInteger amount = BigInteger.TEN;

        MobileCoinClient mobileCoinClient = Environment.makeFreshMobileCoinClient();
        PublicAddress recipient = TestKeysManager.getNextAccountKey().getPublicAddress();
        try {
            BigInteger minimumFee = mobileCoinClient.estimateTotalFee(
                    amount,
                    MobileCoinClient.FeeLevel.MINIMUM
            );
            PendingTransaction pending = mobileCoinClient.prepareTransaction(
                    recipient,
                    amount,
                    minimumFee
            );

            Balance balanceBefore = mobileCoinClient.getBalance();
            mobileCoinClient.submitTransaction(pending.getTransaction());
            Transaction.Status txStatus = waitForTransactionStatus(mobileCoinClient,
                    pending.getTransaction());

            Balance balanceAfter;
            do {
                balanceAfter = mobileCoinClient.getBalance();
            } while (balanceAfter.getBlockIndex().compareTo(txStatus.getBlockIndex()) < 0);
            Assert.assertEquals(balanceBefore.getAmountPicoMob()
                            .subtract(amount)
                            .subtract(minimumFee),
                    balanceAfter.getAmountPicoMob()
            );
        } finally {
            mobileCoinClient.shutdown();
        }
    }

    @Test
    public void test_post_to_serialized_public_address()
            throws InvalidTransactionException, FragmentedAccountException,
            InsufficientFundsException, InvalidFogResponse, FeeRejectedException,
            SerializationException, AttestationException, NetworkException,
            TransactionBuilderException, FogReportException, InvalidUriException {
        MobileCoinClient mobileCoinClient = Environment.makeFreshMobileCoinClient();
        AccountKey recipient = TestKeysManager.getNextAccountKey();
        try {
            byte[] serializedAddress = recipient.getPublicAddress().toByteArray();
            PublicAddress recipientAddress = PublicAddress.fromBytes(serializedAddress);

            BigInteger amount = BigInteger.TEN;
            BigInteger minimumFee = mobileCoinClient.estimateTotalFee(
                    amount,
                    MobileCoinClient.FeeLevel.MINIMUM
            );
            PendingTransaction pending = mobileCoinClient.prepareTransaction(
                    recipientAddress,
                    amount,
                    minimumFee
            );
            mobileCoinClient.submitTransaction(pending.getTransaction());
        } finally {
            mobileCoinClient.shutdown();
        }
    }

    @Test
    public void test_attestation_must_fail() throws NetworkException, InvalidFogResponse,
            InvalidUriException {
        ClientConfig clientConfig = ClientConfig.defaultConfig();
        // change fog verifier to make balance call fail
        clientConfig.fogView = clientConfig.consensus;
        MobileCoinClient mobileCoinClient = new MobileCoinClient(
                TestKeysManager.getNextAccountKey(),
                Environment.FOG_URI,
                Environment.CONSENSUS_URI,
                clientConfig
        );
        mobileCoinClient.setAuthorization(
                TEST_USERNAME,
                TEST_PASSWORD
        );
        try {
            mobileCoinClient.getBalance();
            Assert.fail("Invalid verifier must fail the test");
        } catch (AttestationException ex) {
            // success
        }
    }

    @Test
    public void test_bad_trust_root_must_fail() throws InvalidFogResponse,
            InvalidUriException, AttestationException {
        ClientConfig clientConfig = ClientConfig.defaultConfig();
        // change fog verifier to make balance call fail
        byte[] certificateBytes = Base64.decode(Environment.wrongTrustRootBase64, Base64.DEFAULT);
        Set<X509Certificate> certs = Util.makeCertificatesFromData(certificateBytes);
        clientConfig.fogView.withTrustRoots(certs);
        MobileCoinClient mobileCoinClient = new MobileCoinClient(
                TestKeysManager.getNextAccountKey(),
                Environment.FOG_URI,
                Environment.CONSENSUS_URI,
                clientConfig
        );
        mobileCoinClient.setAuthorization(
                TEST_USERNAME,
                TEST_PASSWORD
        );
        try {
            mobileCoinClient.getBalance();
            Assert.fail("Invalid trust root must fail the test");
        } catch (NetworkException ex) {
            // success
        }
    }



    @Test
    public void test_outgoing_tx_status()
            throws InvalidTransactionException, InterruptedException, FragmentedAccountException,
            AttestationException, InvalidFogResponse, FeeRejectedException,
            InsufficientFundsException, NetworkException, TransactionBuilderException,
            TimeoutException, FogReportException, InvalidUriException {
        MobileCoinClient mobileCoinClient = Environment.makeFreshMobileCoinClient();
        AccountKey recipient = TestKeysManager.getNextAccountKey();
        try {
            BigInteger amount = BigInteger.TEN;
            BigInteger minimumFee = mobileCoinClient.estimateTotalFee(
                    amount,
                    MobileCoinClient.FeeLevel.MINIMUM
            );
            PendingTransaction pending = mobileCoinClient.prepareTransaction(
                    recipient.getPublicAddress(),
                    amount,
                    minimumFee
            );
            mobileCoinClient.submitTransaction(pending.getTransaction());
            Transaction.Status status = waitForTransactionStatus(
                    mobileCoinClient,
                    pending.getTransaction()
            );
            Assert.assertSame(
                    "Valid transaction must be accepted",
                    status,
                    Transaction.Status.ACCEPTED
            );
        } finally {
            mobileCoinClient.shutdown();
        }
    }

    @Test
    public void test_incoming_tx_status()
            throws InvalidTransactionException, InterruptedException, FragmentedAccountException,
            AttestationException, InvalidFogResponse, FeeRejectedException,
            InsufficientFundsException, AmountDecoderException, NetworkException,
            TransactionBuilderException, TimeoutException, FogReportException,
            InvalidReceiptException, InvalidUriException {
        MobileCoinClient senderClient = Environment.makeFreshMobileCoinClient();
        MobileCoinClient recipientClient = Environment.makeFreshMobileCoinClient();
        try {
            BigInteger amount = BigInteger.TEN;
            BigInteger minimumFee = senderClient.estimateTotalFee(
                    amount,
                    MobileCoinClient.FeeLevel.MINIMUM
            );
            PendingTransaction pending = senderClient.prepareTransaction(
                    recipientClient.getAccountKey().getPublicAddress(),
                    amount,
                    minimumFee
            );
            senderClient.submitTransaction(pending.getTransaction());
            Receipt receipt = pending.getReceipt();
            Assert.assertTrue(
                    "A valid receipt is expected",
                    receipt.isValid(recipientClient.getAccountKey())
            );
            Assert.assertEquals(
                    receipt.getAmount(recipientClient.getAccountKey()),
                    amount
            );
            try {
                // must fail
                receipt.getAmount(TestKeysManager.getNextAccountKey());
                Assert.fail("Must throw an exception when the amount cannot be decoded");
            } catch (AmountDecoderException ignore) {
            }
            Receipt.Status status = waitForReceiptStatus(recipientClient, pending.getReceipt());
            Assert.assertSame(
                    "Valid transaction must be accepted",
                    status,
                    Receipt.Status.RECEIVED
            );
        } finally {
            senderClient.shutdown();
            recipientClient.shutdown();
        }
    }

    @Test
    public void test_zero_coin_value()
            throws InvalidTransactionException, FragmentedAccountException,
            InsufficientFundsException, InvalidFogResponse, FeeRejectedException,
            AttestationException, NetworkException,
            TransactionBuilderException, InterruptedException, TimeoutException,
            FogReportException, InvalidReceiptException, InvalidUriException {
        MobileCoinClient senderClient = Environment.makeFreshMobileCoinClient();
        MobileCoinClient recipientClient = Environment.makeFreshMobileCoinClient();

        Balance initialBalance = recipientClient.getBalance();
        try {
            BigInteger amount = BigInteger.ZERO;
            BigInteger minimumFee = senderClient.estimateTotalFee(
                    amount,
                    MobileCoinClient.FeeLevel.MINIMUM
            );
            PendingTransaction pending = senderClient.prepareTransaction(
                    recipientClient.getAccountKey().getPublicAddress(),
                    amount,
                    minimumFee
            );
            senderClient.submitTransaction(pending.getTransaction());

            Receipt.Status status = waitForReceiptStatus(recipientClient, pending.getReceipt());
            Assert.assertEquals(status, Receipt.Status.RECEIVED);
            Balance finalBalance = recipientClient.getBalance();
            Assert.assertEquals(
                    initialBalance.getAmountPicoMob(),
                    finalBalance.getAmountPicoMob()
            );

            // make sure a zero value unspent TxOut exists
            UnsignedLong startBlock = initialBalance.getBlockIndex();
            UnsignedLong endBlock = finalBalance.getBlockIndex();
            List<OwnedTxOut> txOuts = recipientClient.blockClient.scanForTxOutsInBlockRange(
                    new BlockRange(startBlock, endBlock.add(UnsignedLong.ONE)),
                    recipientClient.getAccountKey()
            );
            OwnedTxOut zeroCoinTxOut = null;
            for (OwnedTxOut txOut : txOuts) {
                if (txOut.getValue().equals(BigInteger.ZERO)) {
                    zeroCoinTxOut = txOut;
                    break;
                }
            }
            if (zeroCoinTxOut == null) {
                Assert.fail("Failed to retrieve zero value TxOut");
            }
        } finally {
            senderClient.shutdown();
            recipientClient.shutdown();
        }
    }

    /**
     * Test defragmentation flow
     * 1. Create a temporary account key
     * 2a. Send small denomination TxOuts to that key
     * 2b. Add additional amount to cover defragmentation fees
     * 3. Verify that the account needs defragmentation
     * 4. Defragment the temporary account
     * 5. Send the funds back into the original account
     * (fees will consumed since the test is not a mock)
     */
    @Test
    public void test_fragmented_account()
            throws AttestationException, TransactionBuilderException, NetworkException,
            InvalidFogResponse, InsufficientFundsException, InterruptedException,
            InvalidTransactionException, FragmentedAccountException, FeeRejectedException,
            TimeoutException, FogReportException, InvalidReceiptException, InvalidUriException {

        final int FRAGMENTS_TO_TEST = 20;
        final BigInteger FRAGMENT_AMOUNT = MobileCoinClient.TX_FEE.multiply(BigInteger.TEN);

        AccountKey coinSourceKey = TestKeysManager.getNextAccountKey();
        MobileCoinClient coinSourceClient = Environment.makeFreshMobileCoinClient(coinSourceKey);

        // 1. Create a new fragmented account
        AccountKey fragmentedAccount = AccountKey.createNew(
                Environment.FOG_URI,
                Environment.fogReportId,
                Environment.fogAuthoritySpki
        );

        MobileCoinClient fragmentedClient =
                Environment.makeFreshMobileCoinClient(fragmentedAccount);

        // 2a. Send small denomination TxOuts to the test account
        for (int i = 0; i < FRAGMENTS_TO_TEST; ++i) {
            BigInteger fee = coinSourceClient.estimateTotalFee(FRAGMENT_AMOUNT);
            PendingTransaction pendingTransaction = coinSourceClient.prepareTransaction(
                    fragmentedAccount.getPublicAddress(),
                    FRAGMENT_AMOUNT,
                    fee
            );
            coinSourceClient.submitTransaction(pendingTransaction.getTransaction());
            waitForTransactionStatus(coinSourceClient,
                    pendingTransaction.getTransaction());
            Receipt.Status status = waitForReceiptStatus(
                    fragmentedClient,
                    pendingTransaction.getReceipt()
            );
            if (status != Receipt.Status.RECEIVED) {
                Assert.fail("Unable to receive transaction on a new account");
            }
        }

        // 2c. Add necessary amount to cover the fees (number of optimizations + actual Tx)
        // and verify the transferable amount before and after fees
        int iterations = FRAGMENTS_TO_TEST / UTXOSelector.MAX_INPUTS + 1;
        BigInteger futureFees = MobileCoinClient
                .TX_FEE.multiply(BigInteger.valueOf(iterations))
                .add(MobileCoinClient.INPUT_FEE.multiply(BigInteger.valueOf(FRAGMENTS_TO_TEST)))
                .add(MobileCoinClient.OUTPUT_FEE.multiply(BigInteger.valueOf(iterations)));

        // Verify the transferable amount is calculated correctly
        BigInteger transferableAmount = fragmentedClient.getTransferableAmount();
        BigInteger calculatedTransferableAmount =
                BigInteger.valueOf(FRAGMENTS_TO_TEST).multiply(FRAGMENT_AMOUNT).subtract(futureFees);
        Assert.assertEquals(calculatedTransferableAmount, transferableAmount);

        BigInteger txFee = coinSourceClient.estimateTotalFee(futureFees);
        PendingTransaction pendingTransaction = coinSourceClient.prepareTransaction(
                fragmentedAccount.getPublicAddress(),
                futureFees,
                txFee
        );
        coinSourceClient.submitTransaction(pendingTransaction.getTransaction());
        coinSourceClient.shutdown();
        Receipt.Status status = waitForReceiptStatus(fragmentedClient,
                pendingTransaction.getReceipt());

        if (status != Receipt.Status.RECEIVED) {
            Assert.fail("Failed to send fees to the test account");
        }

        // Verify the transferable amount is calculated correctly with future fees
        transferableAmount = fragmentedClient.getTransferableAmount();
        calculatedTransferableAmount =
                BigInteger.valueOf(FRAGMENTS_TO_TEST).multiply(FRAGMENT_AMOUNT);
        Assert.assertEquals(calculatedTransferableAmount, transferableAmount);

        // 3. Verify the account needs defragmentation
        BigInteger txAmount = FRAGMENT_AMOUNT.multiply(BigInteger.valueOf(FRAGMENTS_TO_TEST));
        if (!fragmentedClient.requiresDefragmentation(txAmount)) {
            Assert.fail("Test account is not fragmented enough for the test");
        }
        // 4. Defragment the temporary account
        fragmentedClient.defragmentAccount(txAmount, new DefragmentationDelegate() {
            @Override
            public void onStart() {
                Logger.d(TAG, "Defragmentation process started");
            }

            @Override
            public boolean onStepReady(@NonNull PendingTransaction defragStepTx,
                                       @NonNull BigInteger fee)
                    throws NetworkException, InvalidTransactionException, AttestationException {
                Logger.d(TAG, "Defragmentation step");
                fragmentedClient.submitTransaction(defragStepTx.getTransaction());
                return true;
            }

            @Override
            public void onComplete() {
                Logger.d(TAG, "Defragmentation process has completed successfully");
            }

            @Override
            public void onCancel() {
                Logger.wtf(TAG, "Defragmentation process was cancelled");
                Assert.fail("Defragmentation process should not be cancelled in this test");
            }
        });
        // 5. Send the funds back to the original wallet
        BigInteger fee = fragmentedClient.estimateTotalFee(txAmount);
        pendingTransaction = fragmentedClient.prepareTransaction(
                coinSourceKey.getPublicAddress(),
                txAmount,
                fee
        );
        fragmentedClient.submitTransaction(pendingTransaction.getTransaction());
        fragmentedClient.shutdown();
    }

    @Test
    public void test_internal_external_get_owned_tx_outs_api()
            throws InvalidFogResponse, NetworkException, AttestationException, InvalidUriException {
        MobileCoinClient mobileCoinClient = Environment.makeFreshMobileCoinClient();
        TxOutStore store = mobileCoinClient.getTxOutStore();
        store.refresh(
                mobileCoinClient.viewClient,
                mobileCoinClient.ledgerClient,
                mobileCoinClient.blockClient
        );
        AccountActivity accountActivity = mobileCoinClient.getAccountActivity();
        UnsignedLong activityBlockCount = accountActivity.getBlockCount();
        UnsignedLong storeBlockCount = store.getCurrentBlockIndex().add(UnsignedLong.ONE);

        Set<OwnedTxOut> private_api_tx_outs = store.getSyncedTxOuts();
        Set<OwnedTxOut> public_api_tx_outs = accountActivity.getAllTxOuts();
        mobileCoinClient.shutdown();

        if (!private_api_tx_outs.equals(public_api_tx_outs)) {
            Assert.fail("Internal and external APIs are inconsistent");
        }

        // check for timestamps
        public_api_tx_outs.forEach(ownedTxOut -> {
            Date receivedTimestamp = ownedTxOut.getReceivedBlockTimestamp();
            Assert.assertNotNull("Received timestamp should not be null", receivedTimestamp);
            Logger.d(TAG, "TxOut received at " + receivedTimestamp.toString());
            if (ownedTxOut.isSpent(UnsignedLong.MAX_VALUE)) {
                Date spentTimestamp = ownedTxOut.getSpentBlockTimestamp();
                Assert.assertNotNull("Spent timestamp should not be null", spentTimestamp);
                Logger.d(TAG, "TxOut spent at " + spentTimestamp.toString());
            }
        });

        Assert.assertEquals("Internal and external APIs are inconsistent",
                activityBlockCount, storeBlockCount);

    }

    @Test
    public void test_tx_and_receipt_accessors() throws InvalidUriException,
            InsufficientFundsException, NetworkException, AttestationException,
            InvalidFogResponse, InvalidTransactionException, TimeoutException,
            InterruptedException, FogReportException, TransactionBuilderException,
            FragmentedAccountException, FeeRejectedException, InvalidReceiptException {
        MobileCoinClient senderClient = Environment.makeFreshMobileCoinClient();
        MobileCoinClient recipientClient = Environment.makeFreshMobileCoinClient();
        BigInteger amount = BigInteger.TEN;
        try {
            BigInteger minimumFee = senderClient.estimateTotalFee(
                    amount,
                    MobileCoinClient.FeeLevel.MINIMUM
            );
            PendingTransaction pending = senderClient.prepareTransaction(
                    recipientClient.getAccountKey().getPublicAddress(),
                    amount,
                    minimumFee
            );

            // verify input key images corresponding to owned txOuts
            AccountActivity activity = senderClient.getAccountActivity();
            Set<KeyImage> keyImages = pending.getTransaction().getKeyImages();
            Set<KeyImage> matches = activity.getAllTxOuts().stream()
                    .map(OwnedTxOut::getKeyImage)
                    .filter(keyImages::contains)
                    .collect(Collectors.toSet());
            Assert.assertEquals("account has to contain all key images used in the " +
                    "transaction", keyImages.size(), matches.size());

            senderClient.submitTransaction(pending.getTransaction());
            waitForTransactionStatus(senderClient, pending.getTransaction());
            waitForReceiptStatus(recipientClient, pending.getReceipt());

            // verify the output of the receipt is valid
            OwnedTxOut receivedTxOut = pending.getReceipt().fetchOwnedTxOut(recipientClient);
            Assert.assertEquals("Receipt amount must be valid", receivedTxOut.getValue(), amount);
            AccountActivity recipientActivity = recipientClient.getAccountActivity();
            Assert.assertTrue("Recipient activity must contain received TxOut",
                    recipientActivity.getAllTxOuts().contains(receivedTxOut));
        } finally {
            senderClient.shutdown();
            recipientClient.shutdown();
        }
    }
}
