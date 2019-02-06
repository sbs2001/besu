/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.consensus.ibft.tests;

import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;

import tech.pegasys.pantheon.consensus.ibft.ConsensusRoundIdentifier;
import tech.pegasys.pantheon.consensus.ibft.IbftHelpers;
import tech.pegasys.pantheon.consensus.ibft.ibftevent.NewChainHead;
import tech.pegasys.pantheon.consensus.ibft.messagedata.ProposalMessageData;
import tech.pegasys.pantheon.consensus.ibft.messagewrappers.Commit;
import tech.pegasys.pantheon.consensus.ibft.messagewrappers.NewRound;
import tech.pegasys.pantheon.consensus.ibft.messagewrappers.Prepare;
import tech.pegasys.pantheon.consensus.ibft.messagewrappers.Proposal;
import tech.pegasys.pantheon.consensus.ibft.messagewrappers.RoundChange;
import tech.pegasys.pantheon.consensus.ibft.payload.MessageFactory;
import tech.pegasys.pantheon.consensus.ibft.payload.RoundChangeCertificate;
import tech.pegasys.pantheon.consensus.ibft.support.RoundSpecificPeers;
import tech.pegasys.pantheon.consensus.ibft.support.TestContext;
import tech.pegasys.pantheon.consensus.ibft.support.TestContextBuilder;
import tech.pegasys.pantheon.consensus.ibft.support.ValidatorPeer;
import tech.pegasys.pantheon.crypto.SECP256K1.KeyPair;
import tech.pegasys.pantheon.ethereum.core.Block;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;

public class GossipTest {

  private final long blockTimeStamp = 100;
  private final Clock fixedClock =
      Clock.fixed(Instant.ofEpochSecond(blockTimeStamp), ZoneId.systemDefault());

  private final int NETWORK_SIZE = 5;

  private final TestContext context =
      new TestContextBuilder()
          .validatorCount(NETWORK_SIZE)
          .indexOfFirstLocallyProposedBlock(0)
          .clock(fixedClock)
          .useGossip(true)
          .build();

  private final ConsensusRoundIdentifier roundId = new ConsensusRoundIdentifier(1, 0);
  private final RoundSpecificPeers peers = context.roundSpecificPeers(roundId);
  private Block block;
  private ValidatorPeer sender;
  private MessageFactory msgFactory;

  @Before
  public void setup() {
    context.getController().start();
    block = context.createBlockForProposalFromChainHead(roundId.getRoundNumber(), 30);
    sender = peers.getProposer();
    msgFactory = sender.getMessageFactory();
  }

  @Test
  public void gossipMessagesToPeers() {
    final Prepare localPrepare =
        context.getLocalNodeMessageFactory().createPrepare(roundId, block.getHash());
    peers.verifyNoMessagesReceivedNonProposing();

    final Proposal proposal = sender.injectProposal(roundId, block);
    // sender node will have a prepare message as an effect of the proposal being sent
    peers.verifyMessagesReceivedNonPropsing(proposal, localPrepare);
    peers.verifyMessagesReceivedProposer(localPrepare);

    final Prepare prepare = sender.injectPrepare(roundId, block.getHash());
    peers.verifyMessagesReceivedNonPropsing(prepare);
    peers.verifyNoMessagesReceivedProposer();

    final Commit commit = sender.injectCommit(roundId, block.getHash());
    peers.verifyMessagesReceivedNonPropsing(commit);
    peers.verifyNoMessagesReceivedProposer();

    final RoundChange roundChange = msgFactory.createRoundChange(roundId, Optional.empty());
    final RoundChangeCertificate roundChangeCert =
        new RoundChangeCertificate(singleton(roundChange.getSignedPayload()));
    final NewRound newRound =
        sender.injectNewRound(roundId, roundChangeCert, proposal.getSignedPayload());
    peers.verifyMessagesReceivedNonPropsing(newRound);
    peers.verifyNoMessagesReceivedProposer();

    sender.injectRoundChange(roundId, Optional.empty());
    peers.verifyMessagesReceivedNonPropsing(roundChange);
    peers.verifyNoMessagesReceivedProposer();
  }

  @Test
  public void onlyGossipOnce() {
    final Prepare prepare = sender.injectPrepare(roundId, block.getHash());
    peers.verifyMessagesReceivedNonPropsing(prepare);

    sender.injectPrepare(roundId, block.getHash());
    peers.verifyNoMessagesReceivedNonProposing();

    sender.injectPrepare(roundId, block.getHash());
    peers.verifyNoMessagesReceivedNonProposing();
  }

  @Test
  public void messageWithUnknownValidatorIsNotGossiped() {
    final KeyPair unknownKeyPair = KeyPair.generate();
    final MessageFactory unknownMsgFactory = new MessageFactory(unknownKeyPair);
    final Proposal unknownProposal = unknownMsgFactory.createProposal(roundId, block);

    sender.injectMessage(
        ProposalMessageData.create(new Proposal(unknownProposal.getSignedPayload())));
    peers.verifyNoMessagesReceived();
  }

  @Test
  public void messageIsNotGossipedToSenderOrCreator() {
    final ValidatorPeer msgCreator = peers.getFirstNonProposer();
    final MessageFactory peerMsgFactory = msgCreator.getMessageFactory();
    final Proposal proposalFromPeer = peerMsgFactory.createProposal(roundId, block);

    sender.injectMessage(ProposalMessageData.create(proposalFromPeer));

    peers.verifyMessagesReceivedNonPropsingExcluding(msgCreator, proposalFromPeer);
    peers.verifyNoMessagesReceivedProposer();
  }

  @Test
  public void futureMessageIsNotGossipedImmediately() {
    ConsensusRoundIdentifier futureRoundId = new ConsensusRoundIdentifier(2, 0);
    msgFactory.createProposal(futureRoundId, block);

    sender.injectProposal(futureRoundId, block);
    peers.verifyNoMessagesReceived();
  }

  @Test
  public void previousHeightMessageIsNotGossiped() {
    final ConsensusRoundIdentifier futureRoundId = new ConsensusRoundIdentifier(0, 0);
    sender.injectProposal(futureRoundId, block);
    peers.verifyNoMessagesReceived();
  }

  @Test
  public void futureMessageGetGossipedLater() {
    final Block signedCurrentHeightBlock =
        IbftHelpers.createSealedBlock(block, peers.sign(block.getHash()));

    ConsensusRoundIdentifier futureRoundId = new ConsensusRoundIdentifier(2, 0);
    Prepare futurePrepare = sender.injectPrepare(futureRoundId, block.getHash());
    peers.verifyNoMessagesReceivedNonProposing();
    ;

    // add block to chain so we can move to next block height
    context.getBlockchain().appendBlock(signedCurrentHeightBlock, emptyList());
    context
        .getController()
        .handleNewBlockEvent(new NewChainHead(signedCurrentHeightBlock.getHeader()));

    peers.verifyMessagesReceivedNonPropsing(futurePrepare);
  }
}
