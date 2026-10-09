from dataclasses import dataclass


@dataclass(frozen=True)
class Snapshot:
    identity: str
    generation: int
    playing: bool
    paused: bool
    can_pause: bool
    can_play: bool
    revision: int = 0


class NightPolicy:
    """Monotonic, fail-closed action policy; no inference of sleep from idle."""
    def __init__(self):
        self.phase = 'OFF'
        self.reason = ''
        self.target = None
        self.recoveries = 0

    def arm(self, now, target, idle_seconds, limit_seconds, quick=False):
        if not target.playing or not target.can_pause:
            raise ValueError('일시정지를 지원하는 재생 중 앱을 선택해 주세요.')
        self.target = target
        self.started = self.last_activity = self.previous = now
        self.idle_seconds, self.limit_seconds = idle_seconds, limit_seconds
        self.quick = quick
        self.phase, self.reason, self.recoveries = 'WATCH', '', 0

    def same(self, current):
        return current is not None and self.target is not None and current.identity == self.target.identity and current.generation == self.target.generation

    def stop(self, reason='보호를 종료했습니다.'):
        self.phase, self.reason = 'DONE', reason

    def tick(self, now, current, input_age):
        if self.phase in ('OFF', 'DONE', 'FAULT'):
            return None
        gap = now - self.previous
        self.previous = now
        if gap < 0 or gap > 10:
            self.stop('절전 또는 관찰 중단으로 보호를 종료했습니다. 다시 시작해 주세요.')
            return None
        if not self.same(current):
            self.stop('대상 앱 또는 재생 항목이 변경되어 보호를 종료했습니다.')
            return None
        if now - self.started >= 8 * 3600:
            self.stop('최대 보호 시간에 도달했습니다.')
            return None
        if self.phase == 'GRACE':
            if not current.paused or not current.can_play or current.revision != self.pause_revision or now - self.paused_at >= 60:
                self.stop('재생을 멈췄습니다. 보호가 종료되었습니다.')
            return None
        if not current.playing or not current.can_pause:
            self.stop('재생이 멈추었거나 제어할 수 없어 보호를 종료했습니다.')
            return None
        if input_age is None:
            self.last_activity = now
        else:
            self.last_activity = max(self.last_activity, now - input_age)
        if self.phase == 'COOLDOWN':
            if now < self.cooldown_until:
                return None
            self.phase = 'WATCH'
            self.started = self.last_activity = now
        if self.phase == 'WARNING':
            if input_age is not None and input_age < 2:
                self.cancel(now)
            elif now >= self.warning_until:
                self.phase = 'PAUSING'
                return 'PAUSE'
            return None
        hard_limit = self.limit_seconds > 0 and now - self.started >= self.limit_seconds
        idle_limit = self.idle_seconds > 0 and input_age is not None and now - self.last_activity >= self.idle_seconds
        if hard_limit or idle_limit:
            self.reason = '선택한 최대 재생 시간' if hard_limit else '키보드·마우스 입력 없음'
            self.phase = 'WARNING'
            self.warning_until = now + 15
        return None

    def cancel(self, now):
        self.recoveries += 1
        if self.quick or self.recoveries >= 2:
            self.stop('사용자가 계속 재생을 선택했습니다. 보호가 종료되었습니다.')
        else:
            self.phase = 'COOLDOWN'
            self.cooldown_until = now + 900
            self.started = self.last_activity = now

    def pause_result(self, now, current, confirmed):
        if self.phase != 'PAUSING':
            return
        if confirmed and self.same(current) and current.paused:
            self.phase, self.paused_at = 'GRACE', now
            self.pause_revision = current.revision
        else:
            self.phase, self.reason = 'FAULT', '일시정지 확인에 실패했습니다. 자동 제어를 중단했습니다.'

    def may_undo(self, now, current):
        return self.phase == 'GRACE' and now - self.paused_at < 60 and self.same(current) and current.paused and current.can_play and current.revision == self.pause_revision
