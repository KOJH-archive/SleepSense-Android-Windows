import asyncio
import queue
import time
from PySide6.QtCore import QThread, Signal
from .media import MediaAccess
from .native import input_age
from .policy import NightPolicy


class Worker(QThread):
    update = Signal(object)

    def __init__(self):
        super().__init__()
        self.requests = queue.Queue()
        self.policy = NightPolicy()
        self.running = True

    def send(self, action, *args):
        self.requests.put((action, args))

    def run(self):
        from winrt.runtime import init_apartment, uninit_apartment, ApartmentType
        try:
            init_apartment(ApartmentType.MULTI_THREADED)
            try:
                asyncio.run(self.loop())
            finally:
                uninit_apartment()
        except Exception as exc:
            self.update.emit({'rows': [], 'phase': 'FAULT', 'reason': f'Windows 연결 실패 ({type(exc).__name__})', 'remaining': 0})

    async def loop(self):
        media = MediaAccess()
        try:
            await media.connect()
            while self.running:
                now = time.monotonic()
                rows = media.refresh()
                while not self.requests.empty():
                    action, args = self.requests.get_nowait()
                    if action == 'EXIT':
                        self.running = False
                        self.policy.stop()
                        break
                    if action == 'STOP':
                        self.policy.stop()
                    elif action == 'ARM':
                        key, idle, limit, quick = args
                        selected = next((r for r in rows if r[0] == key), None)
                        if not selected or sum(r[1] == selected[1] for r in rows) != 1:
                            self.policy.stop('같은 앱의 재생 세션이 여러 개이거나 사라졌습니다. 한 개만 남겨 주세요.')
                            continue
                        try:
                            self.policy.arm(now, media.snapshot(key), idle, limit, quick)
                        except (ValueError, AttributeError) as exc:
                            self.policy.stop(str(exc))
                    elif action == 'CONTINUE' and self.policy.phase == 'WARNING':
                        self.policy.cancel(now)
                    elif action == 'UNDO':
                        current = media.snapshot(self.policy.target.identity) if self.policy.target else None
                        if self.policy.may_undo(now, current):
                            target = self.policy.target
                            self.policy.stop('재생 복구를 확인하고 있습니다.')
                            success = await media.command(target.identity, target.generation, play=True, expected_revision=current.revision)
                            restored = await self.confirm(media, target, playing=True) if success else None
                            if restored:
                                # A quick test ends here. Normal protection has a 15-minute respite.
                                self.policy.phase = 'GRACE'
                                self.policy.cancel(time.monotonic())
                            else:
                                self.policy.stop('재생 복구를 확인하지 못했습니다. 해당 앱에서 재생해 주세요.')
                if not self.running:
                    break
                current = media.snapshot(self.policy.target.identity) if self.policy.target else None
                decision = self.policy.tick(time.monotonic(), current, input_age())
                if decision == 'PAUSE':
                    target = self.policy.target
                    success = await media.command(target.identity, target.generation)
                    confirmed = await self.confirm(media, target, playing=False) if success else None
                    # No blind retries: a failed or ambiguous command ends this night's action.
                    self.policy.pause_result(time.monotonic(), confirmed, confirmed is not None)
                remaining = 0
                if self.policy.phase == 'WARNING':
                    remaining = max(0, int(self.policy.warning_until - time.monotonic()) + 1)
                elif self.policy.phase == 'GRACE':
                    remaining = max(0, int(self.policy.paused_at + 60 - time.monotonic()))
                self.update.emit({'rows': [(k, source, s.playing, s.can_pause) for k, source, s in rows],
                                  'phase': self.policy.phase, 'reason': self.policy.reason,
                                  'remaining': remaining})
                await asyncio.sleep(0.5)
        except Exception as exc:
            self.policy.phase = 'FAULT'
            self.update.emit({'rows': [], 'phase': 'FAULT', 'reason': f'Windows 재생 제어를 사용할 수 없습니다. ({type(exc).__name__})', 'remaining': 0})
        finally:
            media.close()

    async def confirm(self, media, target, playing):
        deadline = time.monotonic() + 5
        while self.running and time.monotonic() < deadline:
            # Refresh detects disappeared sessions even when no callback was delivered yet.
            media.refresh()
            current = media.snapshot(target.identity)
            if not self.policy.same(current):
                return None
            if current.playing if playing else current.paused:
                return current
            # Exit/stop cancels further verification and never triggers a play command.
            if not self.requests.empty():
                return None
            await asyncio.sleep(0.2)
        return None
