from motoparty_peer.jitter import FEC, FRAME, PLC, SILENCE, JitterBuffer

F = 320


class Sim:
    """Drives a JitterBuffer with a 20 ms pull clock and scheduled arrivals."""

    def __init__(self, seq0=100, ts0=10_000, t0=0):
        self.jb = JitterBuffer(t0)
        self.now = t0
        self.seq0, self.ts0 = seq0, ts0
        self.arrivals = []  # (time, kind, seq, ts, payload)
        self.out = []  # (time, decision)

    def send(self, t, i, frame_index=None, seq=None, keepalive=False):
        """Packet number i (seq offset), carrying frame frame_index (default i), arriving at t."""
        fi = i if frame_index is None else frame_index
        s = (self.seq0 + (i if seq is None else seq)) & 0xFFFF
        ts = (self.ts0 + fi * F) & 0xFFFFFFFF
        self.arrivals.append((t, "ka" if keepalive else "audio", s, ts, f"p{fi}".encode()))

    def run(self, until, pull_phase=10):
        self.arrivals.sort(key=lambda a: a[0])
        while self.now <= until:
            # deliver everything up to now, then pull (pulls happen at k*20 + phase)
            while self.arrivals and self.arrivals[0][0] <= self.now:
                t, kind, s, ts, p = self.arrivals.pop(0)
                if kind == "ka":
                    self.jb.push_keepalive(s, t)
                else:
                    self.jb.push(s, ts, p, t)
            if self.now % 20 == pull_phase:
                self.out.append((self.now, self.jb.pull(self.now)))
            self.now += 1

    def played(self):
        return [d.payload.decode() for _, d in self.out if d.kind == FRAME]

    def kinds(self):
        return [d.kind for _, d in self.out]


def steady(sim, n, start=0, first=0, period=20):
    for i in range(n):
        sim.send(start + i * period, first + i)


def test_in_order_stream_plays_everything_at_target_latency():
    sim = Sim()
    steady(sim, 100)
    sim.run(2100)
    assert sim.played() == [f"p{i}" for i in range(100)]
    s = sim.jb.stats
    assert (s.fec, s.plc, s.late, s.underruns) == (0, 0, 0, 0)
    assert sim.jb.target_ms == 40
    first = next(t for t, d in sim.out if d.kind == FRAME)
    assert 20 < first <= 60  # frame 0 arrived at t=0; playout starts once 40 ms are buffered


def test_single_loss_is_recovered_with_fec_from_successor():
    sim = Sim()
    for i in range(30):
        if i != 10:
            sim.send(i * 20, i)
    sim.run(700)
    ds = [d for _, d in sim.out if d.kind != SILENCE]
    idx = next(k for k, d in enumerate(ds) if d.kind == FEC)
    assert ds[idx].payload == b"p11"  # FEC for frame 10 comes from packet 11
    assert ds[idx - 1].payload == b"p9" and ds[idx + 1].payload == b"p11"
    assert sim.jb.stats.fec == 1 and sim.jb.stats.plc == 0


def test_burst_loss_plc_then_fec():
    sim = Sim()
    for i in range(30):
        if i not in (10, 11, 12):
            sim.send(i * 20, i)
    sim.run(700)
    ks = [d.kind for _, d in sim.out if d.kind != SILENCE]
    i9 = sim.played().index("p9")
    seq = ks[ks.index(FRAME) + i9 + 1 : ks.index(FRAME) + i9 + 5]
    assert seq == [PLC, PLC, FEC, FRAME]
    s = sim.jb.stats
    # at 40 ms depth packet 13 is not there yet when frame 10 is due: those count as starved
    assert s.fec == 1 and s.plc + s.starved - 3 == 2  # (3 = end-of-stream concealment)


def test_dtx_gap_is_silence_not_loss():
    sim = Sim()
    # frames 0..9, then DTX: frames 10..59 not sent (seq stays contiguous), resume at frame 60
    for i in range(10):
        sim.send(i * 20, i)
    for k in range(10):
        sim.send((60 + k) * 20, 10 + k, frame_index=60 + k)
    sim.run(1500)
    s = sim.jb.stats
    assert s.fec == 0 and s.plc == 0 and s.underruns == 0 and s.late == 0
    assert sim.played() == [f"p{i}" for i in list(range(10)) + list(range(60, 70))]
    assert sim.jb.target_ms == 40


def test_keepalives_do_not_count_as_loss():
    sim = Sim()
    for i in range(5):
        sim.send(i * 20, i)
    # talker silent: two keepalives use seq 5 and 6, audio resumes with seq 7 at frame 100
    sim.send(1100, 5, keepalive=True)
    sim.send(2100, 6, keepalive=True)
    for k in range(5):
        sim.send(2200 + k * 20, 7 + k, frame_index=110 + k)
    sim.run(2600)
    assert sim.jb.stats.plc == 0 and sim.jb.stats.fec == 0
    assert sim.played()[-5:] == [f"p{110 + k}" for k in range(5)]


def test_stall_is_underrun_reanchors_and_target_decays():
    sim = Sim()
    steady(sim, 20)
    # Wi-Fi stall: packets 20..25 are held up and arrive together 150 ms late
    for i in range(20, 26):
        sim.send(20 * 20 + 150, i)
    for i in range(26, 60):
        sim.send(i * 20, i)
    sim.run(1500)
    s = sim.jb.stats
    assert s.underruns == 1 and s.late == 1
    assert sim.jb.target_ms == 60
    # re-anchored on the late direct successor: nothing was lost, only delayed
    assert sim.played() == [f"p{i}" for i in range(60)]
    assert s.fec == 0 and s.plc == 0
    # 10 s without another underrun -> back to 40
    sim.run(1500 + 10_100)
    assert sim.jb.target_ms == 40


def test_single_late_packet_after_fec_is_dropped_and_raises_target():
    sim = Sim()
    steady(sim, 20)
    sim.send(20 * 20 + 150, 20)  # only packet 20 is late; 21 arrives on time
    for i in range(21, 40):
        sim.send(i * 20, i)
    sim.run(1200)
    s = sim.jb.stats
    assert s.fec == 1  # frame 20 was recovered from packet 21's FEC before 20 turned up
    assert s.late == 1 and s.underruns == 1 and sim.jb.target_ms == 60
    assert "p20" not in sim.played()
    assert s.stretched == 1  # the extra 20 ms is inserted at the next silence


def test_target_is_clamped():
    jb = JitterBuffer(0)
    for n in range(20):
        jb._underrun(n)
    assert jb.target_ms == 200
    for k in range(1, 30):
        jb.pull(k * 10_000 + 20)
    assert jb.target_ms == 40


def test_reordering_within_buffer():
    sim = Sim()
    for i in range(20):
        t = i * 20
        if i == 5:
            t = 6 * 20 + 2  # 5 arrives just after 6, both well before playout
        sim.send(t, i)
    sim.run(600)
    assert sim.played() == [f"p{i}" for i in range(20)]
    assert sim.jb.stats.underruns == 0


def test_duplicates_dropped():
    sim = Sim()
    steady(sim, 10)
    sim.send(45, 2)
    sim.run(400)
    assert sim.played() == [f"p{i}" for i in range(10)]
    assert sim.jb.stats.duplicates == 1


def test_seq_and_ts_wraparound():
    sim = Sim(seq0=65530, ts0=(1 << 32) - 5 * F)
    steady(sim, 30)
    sim.send(30 * 20, 30)
    sim.run(800)
    assert sim.played() == [f"p{i}" for i in range(31)]
    assert sim.jb.stats.plc == 0 and sim.jb.stats.fec == 0


def test_lone_packet_still_plays():
    sim = Sim()
    sim.send(0, 0)
    sim.run(200)
    assert sim.played() == ["p0"]


def test_excess_depth_is_trimmed():
    sim = Sim()
    # 6 frames arrive at once (burst after a stall), then a steady stream: depth ~120 ms
    for i in range(6):
        sim.send(0, i)
    for i in range(6, 200):
        sim.send((i - 5) * 20, i)
    sim.run(4200)
    assert sim.jb.stats.trimmed >= 2
    assert sim.jb.depth_ms <= sim.jb.target_ms + 20


def test_random_jitter_and_loss_accounts_for_every_frame():
    import random

    rnd = random.Random(7)
    sim = Sim(seq0=65000, ts0=(1 << 32) - 1000 * F)
    lost = set()
    for i in range(1500):  # 30 s
        if rnd.random() < 0.05:
            lost.add(i)
            continue
        delay = rnd.choice([0, 0, 0, 5, 10, 15, 30, 70]) if rnd.random() < 0.3 else rnd.randint(0, 8)
        sim.send(i * 20 + delay, i)
    sim.run(1500 * 20 + 500)
    s = sim.jb.stats
    played = set(int(p[1:]) for p in sim.played())
    assert played.isdisjoint(lost)
    # every frame is either played, recovered (FEC/PLC), or dropped as late/trimmed
    assert len(played) + s.fec + s.plc + s.starved >= 1500 - s.late - s.trimmed - 5
    assert 40 <= sim.jb.target_ms <= 200
    assert s.fec > 0 and s.underruns > 0
    # order is preserved
    order = [int(p[1:]) for p in sim.played()]
    assert order == sorted(order)
