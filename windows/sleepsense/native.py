import ctypes
from ctypes import wintypes


class LASTINPUTINFO(ctypes.Structure):
    _fields_ = [('cbSize', wintypes.UINT), ('dwTime', wintypes.DWORD)]


def input_age():
    info = LASTINPUTINFO(ctypes.sizeof(LASTINPUTINFO), 0)
    if not ctypes.windll.user32.GetLastInputInfo(ctypes.byref(info)):
        return None
    ctypes.windll.kernel32.GetTickCount.restype = wintypes.DWORD
    elapsed = (ctypes.windll.kernel32.GetTickCount() - info.dwTime) & 0xffffffff
    return elapsed / 1000 if elapsed < 86400000 else None
