import asyncio
import threading
import uuid
from winrt.windows.media.control import (
    GlobalSystemMediaTransportControlsSessionManager as Manager,
    GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
)
from .policy import Snapshot


class MediaAccess:
    def __init__(self):
        self.manager = None
        self.entries = {}
        self.lock = threading.Lock()
        self.epoch = 0

    async def connect(self):
        self.manager = await asyncio.wait_for(Manager.request_async(), 5)
        self.manager_token = self.manager.add_sessions_changed(self.sessions_changed)

    def sessions_changed(self, *_):
        with self.lock:
            self.epoch += 1

    def metadata_changed(self, key):
        def changed(*_):
            with self.lock:
                if key in self.entries:
                    self.entries[key]['generation'] += 1
        return changed

    def playback_changed(self, key):
        def changed(*_):
            with self.lock:
                if key in self.entries:
                    self.entries[key]['revision'] += 1
        return changed

    def refresh(self):
        sessions = list(self.manager.get_sessions())
        with self.lock:
            old = list(self.entries.items())
        for key, entry in old:
            if not any(entry['session'] == s for s in sessions):
                self.remove(key, entry)
        for session in sessions:
            if any(e['session'] == session for e in self.entries.values()):
                continue
            key = str(uuid.uuid4())
            entry = {'session': session, 'generation': 0, 'revision': 0, 'source': session.source_app_user_model_id}
            with self.lock:
                self.entries[key] = entry
            entry['token'] = session.add_media_properties_changed(self.metadata_changed(key))
            entry['playback_token'] = session.add_playback_info_changed(self.playback_changed(key))
        rows = []
        for key, entry in list(self.entries.items()):
            snapshot = self.snapshot(key)
            if snapshot:
                rows.append((key, entry['source'], snapshot))
        return rows

    def snapshot(self, key):
        with self.lock:
            entry = self.entries.get(key)
            if not entry:
                return None
            generation = (self.epoch << 32) + entry['generation']
            revision = entry['revision']
        info = entry['session'].get_playback_info()
        return Snapshot(key, generation, info.playback_status == Status.PLAYING,
                        info.playback_status == Status.PAUSED,
                        info.controls.is_pause_enabled, info.controls.is_play_enabled, revision)

    async def command(self, key, generation, play=False, expected_revision=None):
        current = self.snapshot(key)
        if current is None or current.generation != generation:
            return False
        if expected_revision is not None and current.revision != expected_revision:
            return False
        if play and not (current.paused and current.can_play):
            return False
        if not play and not (current.playing and current.can_pause):
            return False
        session = self.entries[key]['session']
        operation = session.try_play_async() if play else session.try_pause_async()
        return bool(await asyncio.wait_for(operation, 5))

    def remove(self, key, entry):
        try:
            entry['session'].remove_media_properties_changed(entry['token'])
            entry['session'].remove_playback_info_changed(entry['playback_token'])
        finally:
            with self.lock:
                self.entries.pop(key, None)

    def close(self):
        for key, entry in list(self.entries.items()):
            try:
                self.remove(key, entry)
            except Exception:
                pass
        if self.manager:
            self.manager.remove_sessions_changed(self.manager_token)
