package io.github.cheshmakzanoon.portfolio.quorumkv;

import static io.github.cheshmakzanoon.portfolio.quorumkv.Model.*;

import java.io.*;
import java.util.*;

/** Figure-2 Raft transition owner. Effects depending on stable state wait for Persisted. */
public final class Raft {
  public enum Role {
    FOLLOWER,
    CANDIDATE,
    LEADER
  }

  public sealed interface Event permits Tick, Receive, Persisted, Mutate, Read, TakeSnapshot {}

  public record Tick(long now) implements Event {}

  public record Receive(int peer, Rpc rpc) implements Event {}

  public record Persisted(long token) implements Event {}

  public record Mutate(long request, Command command) implements Event {}

  public record Read(long request, Key key) implements Event {}

  public record TakeSnapshot() implements Event {}

  public sealed interface Effect permits Persist, Send, Reply, Applied {}

  public record Persist(long token, Durable durable) implements Effect {}

  public record Send(int peer, Rpc rpc) implements Effect {}

  public record Reply(long request, Result result) implements Effect {}

  public record Applied(long index, long term, Command command, Result result) implements Effect {}

  private record Sent(long term, long previous, long last, long read) {}

  private static final class Reading {
    final long request, safe, expires;
    final Key key;
    final Set<Integer> acks = new HashSet<>();

    Reading(long request, long safe, Key key, int self, long expires) {
      this.request = request;
      this.expires = expires;
      this.safe = safe;
      this.key = key;
      acks.add(self);
    }
  }

  private final int id;
  private final long boot;
  private final Random electionRandom;
  private long term,
      commit,
      applied,
      clock,
      deadline,
      heartbeat,
      serial,
      persistSerial,
      pendingPersist;
  private int vote, leader = -1;
  private Snapshot snapshot;
  private final ArrayList<Entry> log = new ArrayList<>();
  private Registers registers;
  private Role role = Role.FOLLOWER;
  private final Set<Integer> votes = new HashSet<>();
  private final Map<Integer, Long> voteRequests = new HashMap<>();
  private final Map<Integer, SnapshotRequest> snapshotRequests = new HashMap<>();
  private final Map<Integer, Long> next = new HashMap<>(), match = new HashMap<>();
  private final Map<Integer, Map<Long, Sent>> sent =
      Map.of(0, new LinkedHashMap<>(), 1, new LinkedHashMap<>(), 2, new LinkedHashMap<>());
  private final Map<Long, Long> clients = new HashMap<>();
  private final Map<Long, Reading> reads = new LinkedHashMap<>();
  private final ArrayDeque<Event> deferred = new ArrayDeque<>();
  private List<Effect> held = List.of();
  private ArrayList<Effect> out;
  private boolean dirty;
  private ByteArrayOutputStream incoming;
  private long incomingIndex, incomingTerm;
  private byte[] outgoingSnapshot;
  private final Map<Integer, Integer> snapshotOffsets = new HashMap<>();

  public Raft(int id, long boot, Durable d) {
    if (id < 0 || id > 2) throw new IllegalArgumentException("MEMBER");
    this.id = id;
    this.boot = boot;
    electionRandom = new Random(boot * 0x9e3779b97f4a7c15L + id);
    term = d.term();
    vote = d.vote();
    commit = d.commit();
    snapshot = d.snapshot();
    log.addAll(d.log());
    registers = new Registers(snapshot);
    applied = snapshot.index();
    applyRecovered();
    resetDeadline();
  }

  public List<Effect> accept(Event event) {
    if (event instanceof Persisted a) {
      if (a.token() != pendingPersist || pendingPersist == 0) return List.of();
      pendingPersist = 0;
      out = new ArrayList<>(held);
      if (applied < snapshot.index()) {
        registers = new Registers(snapshot);
        applied = snapshot.index();
      }
      apply();
      var result = new ArrayList<>(out);
      held = List.of();
      while (pendingPersist == 0 && !deferred.isEmpty())
        result.addAll(accept(deferred.removeFirst()));
      return List.copyOf(result);
    }
    if (pendingPersist != 0) {
      if (event instanceof Tick && deferred.peekLast() instanceof Tick) deferred.removeLast();
      if (deferred.size() >= MAX_PENDING) {
        if (event instanceof Mutate m)
          return List.of(new Reply(m.request(), new Result("RESOURCE_EXHAUSTED", null, leader)));
        if (event instanceof Read r)
          return List.of(new Reply(r.request(), new Result("RESOURCE_EXHAUSTED", null, leader)));
        return List.of(); // RPC overload is network loss; bounded retry is driven by heartbeats.
      }
      deferred.add(event);
      return List.of();
    }
    out = new ArrayList<>();
    dirty = false;
    switch (event) {
      case Tick t -> tick(t.now());
      case Receive r -> receive(r.peer(), r.rpc());
      case Mutate m -> mutate(m);
      case Read r -> read(r);
      case TakeSnapshot ignored -> compact();
      case Persisted ignored -> throw new AssertionError();
    }
    if (dirty) {
      held = List.copyOf(out);
      pendingPersist = ++persistSerial;
      return List.of(new Persist(pendingPersist, durable()));
    }
    return List.copyOf(out);
  }

  public Durable durable() {
    return new Durable(term, vote, commit, snapshot, log);
  }

  public long applied() {
    return applied;
  }

  public long commit() {
    return commit;
  }

  public long term() {
    return term;
  }

  public Role role() {
    return role;
  }

  public int leader() {
    return leader;
  }

  public int id() {
    return id;
  }

  public Snapshot view() {
    return registers.snapshot(applied, termAt(applied));
  }

  public boolean waitingForDisk() {
    return pendingPersist != 0;
  }

  private long request() {
    return (boot << 32) ^ (++serial);
  }

  private long last() {
    return snapshot.index() + log.size();
  }

  private long termAt(long index) {
    if (index == snapshot.index()) return snapshot.term();
    if (index < snapshot.index() || index > last()) return -1;
    return log.get(Math.toIntExact(index - snapshot.index() - 1)).term();
  }

  private Entry entry(long index) {
    return log.get(Math.toIntExact(index - snapshot.index() - 1));
  }

  private void resetDeadline() {
    deadline = clock + 250 + electionRandom.nextInt(250);
  }

  private void tick(long now) {
    clock = Math.max(clock, now);
    var pendingReads = reads.values().iterator();
    while (pendingReads.hasNext()) {
      Reading reading = pendingReads.next();
      if (clock >= reading.expires) {
        out.add(new Reply(reading.request, new Result("UNAVAILABLE", null, leader)));
        pendingReads.remove();
      }
    }
    if (role != Role.LEADER && clock >= deadline) elect();
    else if (role == Role.LEADER && clock >= heartbeat) {
      broadcast(0);
      heartbeat = clock + 50;
    }
  }

  private void elect() {
    term++;
    vote = id;
    role = Role.CANDIDATE;
    leader = -1;
    votes.clear();
    votes.add(id);
    reads.clear();
    sent.values().forEach(Map::clear);
    dirty = true;
    resetDeadline();
    for (int p = 0; p < 3; p++)
      if (p != id) {
        long req = request();
        voteRequests.put(p, req);
        out.add(new Send(p, new VoteRequest(term, req, id, last(), termAt(last()))));
      }
  }

  private void stepDown(long newTerm) {
    if (newTerm > term) {
      term = newTerm;
      vote = -1;
      dirty = true;
    }
    role = Role.FOLLOWER;
    leader = -1;
    votes.clear();
    reads
        .values()
        .forEach(r -> out.add(new Reply(r.request, new Result("UNKNOWN_OUTCOME", null, -1))));
    reads.clear();
    clients.values().forEach(r -> out.add(new Reply(r, new Result("UNKNOWN_OUTCOME", null, -1))));
    clients.clear();
    sent.values().forEach(Map::clear);
    snapshotOffsets.clear();
    snapshotRequests.clear();
    incoming = null;
    outgoingSnapshot = null;
  }

  private void receive(int peer, Rpc rpc) {
    if (peer < 0 || peer > 2 || peer == id) return;
    if (rpc.term() > term) stepDown(rpc.term());
    switch (rpc) {
      case VoteRequest r -> vote(peer, r);
      case VoteResponse r -> voted(peer, r);
      case AppendRequest r -> append(peer, r);
      case AppendResponse r -> appended(peer, r);
      case SnapshotRequest r -> install(peer, r);
      case SnapshotResponse r -> installed(peer, r);
    }
  }

  private void vote(int peer, VoteRequest r) {
    boolean grant =
        r.term() == term
            && r.candidate() == peer
            && (vote == -1 || vote == peer)
            && (r.lastTerm() > termAt(last())
                || (r.lastTerm() == termAt(last()) && r.lastIndex() >= last()));
    if (grant) {
      vote = peer;
      dirty = true;
      resetDeadline();
    }
    out.add(new Send(peer, new VoteResponse(term, r.request(), grant)));
  }

  private void voted(int peer, VoteResponse r) {
    if (role != Role.CANDIDATE
        || r.term() != term
        || !r.granted()
        || !Objects.equals(voteRequests.get(peer), r.request())) return;
    votes.add(peer);
    if (votes.size() < 2) return;
    role = Role.LEADER;
    leader = id;
    for (int p = 0; p < 3; p++) {
      next.put(p, last() + 1);
      match.put(p, p == id ? last() : 0L);
    }
    log.add(new Entry(last() + 1, term, null));
    match.put(id, last());
    dirty = true;
    broadcast(0);
    heartbeat = clock + 50;
  }

  private void append(int peer, AppendRequest r) {
    if (r.term() != term || r.leader() != peer) {
      out.add(new Send(peer, new AppendResponse(term, r.request(), false, last(), r.read())));
      return;
    }
    if (role != Role.FOLLOWER) stepDown(term);
    leader = peer;
    resetDeadline();
    if (termAt(r.previousIndex()) != r.previousTerm()) {
      out.add(new Send(peer, new AppendResponse(term, r.request(), false, last(), r.read())));
      return;
    }
    long index = r.previousIndex();
    for (Entry incomingEntry : r.entries()) {
      index++;
      if (incomingEntry.index() != index || incomingEntry.term() > term)
        throw new IllegalArgumentException("MALFORMED_APPEND");
      if (index <= last() && termAt(index) != incomingEntry.term()) {
        if (index <= commit) throw new IllegalStateException("COMMITTED_TRUNCATION");
        log.subList(Math.toIntExact(index - snapshot.index() - 1), log.size()).clear();
        dirty = true;
      }
      if (index > last()) {
        log.add(incomingEntry);
        dirty = true;
      }
    }
    long newCommit = Math.min(r.commit(), index);
    if (newCommit > commit) {
      commit = newCommit;
      dirty = true;
    }
    out.add(new Send(peer, new AppendResponse(term, r.request(), true, index, r.read())));
  }

  private void appended(int peer, AppendResponse r) {
    if (role != Role.LEADER || r.term() != term) return;
    Sent s = sent.get(peer).remove(r.request());
    if (s == null || s.term() != term) return;
    if (r.success()) {
      if (r.match() != s.last() || r.read() != s.read()) return;
      match.put(peer, Math.max(match.get(peer), r.match()));
      next.put(peer, Math.max(next.get(peer), r.match() + 1));
      advance();
      if (r.read() != 0) {
        Reading reading = reads.get(r.read());
        if (reading != null) {
          reading.acks.add(peer);
          completeReads();
        }
      }
      if (next.get(peer) <= last()) replicate(peer, 0);
    } else if (s.previous() >= match.get(peer) && s.last() >= next.get(peer) - 1) {
      next.put(peer, Math.max(match.get(peer) + 1, Math.min(next.get(peer) - 1, s.previous())));
      replicate(peer, s.read());
    }
  }

  private void advance() {
    long candidate = last();
    while (candidate > commit) {
      int count = 1;
      for (int p = 0; p < 3; p++) if (p != id && match.getOrDefault(p, 0L) >= candidate) count++;
      if (count >= 2 && termAt(candidate) == term) {
        commit = candidate;
        dirty = true;
        broadcast(0);
        break;
      }
      candidate--;
    }
  }

  private void broadcast(long read) {
    for (int p = 0; p < 3; p++) if (p != id) replicate(p, read);
  }

  private void replicate(int peer, long read) {
    if (sent.get(peer).size() >= MAX_PENDING) {
      var it = sent.get(peer).keySet().iterator();
      it.next();
      it.remove();
    }
    long n = next.getOrDefault(peer, last() + 1);
    if (n <= snapshot.index()) {
      sendSnapshot(peer);
      return;
    }
    long end = Math.min(last(), n + 31);
    List<Entry> entries =
        n <= end
            ? List.copyOf(
                log.subList(
                    Math.toIntExact(n - snapshot.index() - 1),
                    Math.toIntExact(end - snapshot.index())))
            : List.of();
    long req = request();
    sent.get(peer).put(req, new Sent(term, n - 1, end, read));
    out.add(
        new Send(
            peer, new AppendRequest(term, req, id, n - 1, termAt(n - 1), entries, commit, read)));
  }

  private void mutate(Mutate m) {
    if (role != Role.LEADER) {
      out.add(new Reply(m.request(), new Result("NOT_LEADER", null, leader)));
      return;
    }
    if (clients.size() >= MAX_PENDING || log.size() > 4096) {
      out.add(new Reply(m.request(), new Result("RESOURCE_EXHAUSTED", null, id)));
      return;
    }
    log.add(new Entry(last() + 1, term, m.command()));
    clients.put(last(), m.request());
    match.put(id, last());
    dirty = true;
    broadcast(0);
  }

  private void read(Read r) {
    if (role != Role.LEADER) {
      out.add(new Reply(r.request(), new Result("NOT_LEADER", null, leader)));
      return;
    }
    if (commit == 0 || termAt(commit) != term) {
      out.add(new Reply(r.request(), new Result("UNAVAILABLE", null, id)));
      return;
    }
    if (reads.size() >= MAX_PENDING) {
      out.add(new Reply(r.request(), new Result("RESOURCE_EXHAUSTED", null, id)));
      return;
    }
    long correlation = request();
    reads.put(correlation, new Reading(r.request(), commit, r.key(), id, clock + 1000));
    broadcast(correlation);
  }

  private void completeReads() {
    var it = reads.entrySet().iterator();
    while (it.hasNext()) {
      Reading r = it.next().getValue();
      if (r.acks.size() >= 2 && applied >= r.safe) {
        out.add(new Reply(r.request, registers.get(r.key)));
        it.remove();
      }
    }
  }

  private void applyRecovered() {
    while (applied < commit) {
      Entry e = entry(++applied);
      if (e.command() != null) registers.apply(e.command());
    }
  }

  private void apply() {
    while (applied < commit) {
      Entry e = entry(++applied);
      Result result =
          e.command() == null ? new Result("MARKER", null, -1) : registers.apply(e.command());
      out.add(new Applied(e.index(), e.term(), e.command(), result));
      Long client = clients.remove(e.index());
      if (client != null) out.add(new Reply(client, result));
    }
    completeReads();
  }

  private void compact() {
    if (applied <= snapshot.index()) return;
    snapshot = registers.snapshot(applied, termAt(applied));
    log.subList(0, Math.toIntExact(applied - durableSnapshotIndex())).clear();
    dirty = true;
    outgoingSnapshot = null;
    snapshotOffsets.clear();
    snapshotRequests.clear();
  }

  private long durableSnapshotIndex() {
    return log.isEmpty() ? snapshot.index() : log.getFirst().index() - 1;
  }

  private void sendSnapshot(int peer) {
    if (outgoingSnapshot == null) outgoingSnapshot = Codec.snapshot(snapshot);
    int offset = snapshotOffsets.getOrDefault(peer, 0);
    if (offset >= outgoingSnapshot.length) offset = 0;
    int end = Math.min(outgoingSnapshot.length, offset + 16384);
    SnapshotRequest message =
        new SnapshotRequest(
            term,
            request(),
            id,
            snapshot.index(),
            snapshot.term(),
            offset,
            outgoingSnapshot.length,
            Arrays.copyOfRange(outgoingSnapshot, offset, end));
    snapshotRequests.put(peer, message);
    out.add(new Send(peer, message));
  }

  private void install(int peer, SnapshotRequest r) {
    if (r.term() != term || r.leader() != peer) return;
    if (r.index() < 1 || r.snapshotTerm() < 1 || r.snapshotTerm() > term) return;
    if (role != Role.FOLLOWER) stepDown(term);
    leader = peer;
    resetDeadline();
    if (r.total() < 1
        || r.total() > MAX_SNAPSHOT
        || r.offset() < 0
        || r.offset() + r.chunk().length > r.total()) return;
    if (r.index() <= commit) {
      out.add(new Send(peer, new SnapshotResponse(term, r.request(), r.index(), r.total(), true)));
      return;
    }
    if (r.offset() == 0) {
      incoming = new ByteArrayOutputStream();
      incomingIndex = r.index();
      incomingTerm = r.snapshotTerm();
    }
    if (incoming == null
        || incomingIndex != r.index()
        || incomingTerm != r.snapshotTerm()
        || incoming.size() != r.offset()) {
      out.add(new Send(peer, new SnapshotResponse(term, r.request(), r.index(), 0, false)));
      return;
    }
    incoming.writeBytes(r.chunk());
    if (incoming.size() < r.total()) {
      out.add(
          new Send(
              peer, new SnapshotResponse(term, r.request(), r.index(), incoming.size(), false)));
      return;
    }
    try {
      Snapshot installed = Codec.snapshot(incoming.toByteArray());
      if (installed.index() != r.index() || installed.term() != r.snapshotTerm()) return;
      List<Entry> suffix =
          termAt(r.index()) == r.snapshotTerm()
              ? log.stream().filter(e -> e.index() > r.index()).toList()
              : List.of();
      snapshot = installed;
      log.clear();
      log.addAll(suffix);
      commit = installed.index();
      dirty = true;
      incoming = null;
      out.add(new Send(peer, new SnapshotResponse(term, r.request(), r.index(), r.total(), true)));
    } catch (IOException | IllegalArgumentException e) {
      incoming = null;
    }
  }

  private void installed(int peer, SnapshotResponse r) {
    SnapshotRequest request = snapshotRequests.get(peer);
    if (role != Role.LEADER
        || r.term() != term
        || r.index() != snapshot.index()
        || request == null
        || request.request() != r.request()) return;
    if (r.done()) {
      if (r.nextOffset() != request.total()) return;
      match.put(peer, Math.max(match.get(peer), r.index()));
      next.put(peer, Math.max(next.get(peer), r.index() + 1));
      snapshotOffsets.remove(peer);
      replicate(peer, 0);
    } else {
      if (r.nextOffset() == 0 && request.offset() > 0) {
        snapshotOffsets.put(peer, 0);
        sendSnapshot(peer);
        return;
      }
      if (r.nextOffset() != request.offset() + request.chunk().length
          || r.nextOffset() >= request.total()) return;
      snapshotOffsets.put(peer, r.nextOffset());
      sendSnapshot(peer);
    }
  }
}
