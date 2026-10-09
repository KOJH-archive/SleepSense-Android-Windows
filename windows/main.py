import os
import sys
from pathlib import Path
from PySide6.QtCore import QTimer, Qt
from PySide6.QtGui import QColor, QIcon, QPainter, QPixmap, QFontDatabase
from PySide6.QtWidgets import (QApplication, QWidget, QLabel, QPushButton, QComboBox,
                              QVBoxLayout, QHBoxLayout, QSystemTrayIcon, QMenu, QMessageBox, QScrollArea)
from sleepsense.worker import Worker

PHASE = {'OFF': '준비됨', 'WATCH': '야간 보호 중', 'WARNING': '곧 재생을 멈춥니다',
         'PAUSING': '일시정지 확인 중', 'GRACE': '재생을 멈췄습니다',
         'COOLDOWN': '15분 뒤 보호를 다시 시작합니다', 'DONE': '보호 종료', 'FAULT': '확인이 필요합니다'}


def icon():
    pix = QPixmap(64, 64)
    pix.fill(Qt.GlobalColor.transparent)
    p = QPainter(pix)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QColor('#93c5fd'))
    p.drawEllipse(7, 7, 50, 50)
    p.setBrush(QColor('#101827'))
    p.drawEllipse(23, 0, 44, 44)
    p.end()
    return QIcon(pix)


class Window(QWidget):
    def __init__(self, smoke=False):
        super().__init__()
        self.smoke, self.last_phase, self.exiting = smoke, 'OFF', False
        self.setWindowTitle('SleepSense · 야간 재생 보호')
        self.setWindowIcon(icon())
        self.setMinimumSize(560, 640)
        self.resize(560, 840)
        self.setStyleSheet('''QWidget {background:#101827; color:#e5eaf3; font:15px "Malgun Gothic";}
            QLabel {background:transparent;} QComboBox {background:#1d2b40; padding:6px 12px; min-height:32px; border:1px solid #34445c; border-radius:8px;}
            QPushButton {background:#25354c; padding:6px 12px; min-height:32px; border-radius:8px;} QPushButton:hover {background:#344962;}
            QPushButton:disabled {color:#65758c;} QPushButton#start {background:#2e6389;}
            QMenu {background:#1d2b40;}''')
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.Shape.NoFrame)
        content = QWidget()
        scroll.setWidget(content)
        outer.addWidget(scroll)
        layout = QVBoxLayout(content)
        layout.setContentsMargins(28, 25, 28, 25)
        layout.setSpacing(10)
        title = QLabel('SleepSense')
        title.setStyleSheet('font-size:30px; font-weight:600; color:#b5d5f5;')
        layout.addWidget(title)
        layout.addWidget(QLabel('어두운 밤, 선택한 앱의 재생을 보호합니다.'))
        intro = QLabel('이 버전은 수면을 판정하지 않습니다.\n선택한 시간과 키보드·마우스 입력 여부를 사용합니다.\n카메라·마이크를 사용하지 않고 소리를 내지 않습니다.')
        intro.setWordWrap(True)
        intro.setStyleSheet('color:#a9b8cc; line-height:1.5;')
        layout.addWidget(intro)
        layout.addWidget(QLabel('재생을 멈출 앱'))
        self.targets = QComboBox()
        layout.addWidget(self.targets)
        self.idle = QComboBox()
        for minutes in (0, 30, 45, 60, 90):
            self.idle.addItem('사용 안 함' if not minutes else f'{minutes}분 동안 입력이 없으면', minutes * 60)
        self.idle.setCurrentIndex(2)
        layout.addWidget(QLabel('입력이 없는 시간 · 영상 시청 중에도 시간이 흐릅니다'))
        layout.addWidget(self.idle)
        self.limit = QComboBox()
        for minutes in (0, 30, 60, 90, 120, 180):
            self.limit.addItem('사용 안 함' if not minutes else f'시작 후 {minutes}분', minutes * 60)
        layout.addWidget(QLabel('최대 재생 시간 · 입력을 해도 적용됩니다'))
        layout.addWidget(self.limit)
        self.status = QLabel('준비됨')
        self.status.setStyleSheet('font-size:20px; color:#b5d5f5;')
        layout.addWidget(self.status)
        self.detail = QLabel('동영상이나 음악을 재생하면 지원되는 앱이 나타납니다.')
        self.detail.setWordWrap(True)
        layout.addWidget(self.detail)
        self.start = QPushButton('오늘 밤 보호 시작')
        self.start.setObjectName('start')
        self.start.clicked.connect(lambda: self.arm(False))
        layout.addWidget(self.start)
        self.quick = QPushButton('2분 뒤 실제 일시정지 시험')
        self.quick.clicked.connect(lambda: self.arm(True))
        layout.addWidget(self.quick)
        actions = QHBoxLayout()
        self.undo = QPushButton('계속 재생 / 되돌리기')
        self.undo.clicked.connect(self.continue_play)
        actions.addWidget(self.undo)
        self.stop = QPushButton('보호 종료')
        self.stop.clicked.connect(lambda: self.worker.send('STOP'))
        actions.addWidget(self.stop)
        layout.addLayout(actions)
        foot = QLabel('멈추기 전 15초 예고 · 멈춘 후 60초 되돌리기\n지원되는 Windows 재생 세션만 제어합니다.\n닫으면 작업 표시줄의 숨겨진 아이콘에서 계속 실행됩니다.')
        foot.setWordWrap(True)
        foot.setStyleSheet('color:#889bb5; font-size:12px;')
        layout.addWidget(foot)
        self.tray = QSystemTrayIcon(icon(), self)
        self.tray.setToolTip('SleepSense · 야간 재생 보호')
        menu = QMenu()
        menu.addAction('SleepSense 열기', self.reveal)
        menu.addAction('보호 종료', lambda: self.worker.send('STOP'))
        menu.addAction('완전히 종료', self.quit_app)
        self.tray.setContextMenu(menu)
        self.tray.activated.connect(lambda reason: self.reveal() if reason == QSystemTrayIcon.ActivationReason.Trigger else None)
        if QSystemTrayIcon.isSystemTrayAvailable() and not smoke:
            self.tray.show()
        self.worker = Worker()
        self.worker.update.connect(self.render)
        if not smoke:
            self.worker.start()
        else:
            self.render({'rows': [], 'phase': 'OFF', 'reason': '', 'remaining': 0})

    def arm(self, quick):
        key = self.targets.currentData()
        if not key:
            QMessageBox.information(self, '재생 앱 선택', '지원되는 앱에서 먼저 재생을 시작해 주세요.')
            return
        if not quick and not (self.idle.currentData() or self.limit.currentData()):
            QMessageBox.information(self, '시간 선택', '보호 시간을 하나 이상 선택해 주세요.')
            return
        if quick and QMessageBox.question(self, '실제 재생 시험', '2분 후 15초 예고를 거쳐 선택한 앱을 실제로 멈춥니다. 진행할까요?') != QMessageBox.StandardButton.Yes:
            return
        self.worker.send('ARM', key, 0 if quick else self.idle.currentData(), 120 if quick else self.limit.currentData(), quick)

    def render(self, state):
        selected = self.targets.currentData()
        rows = state['rows']
        existing = [(self.targets.itemData(i), self.targets.itemText(i)) for i in range(self.targets.count())]
        wanted = [(key, source + (' · 재생 중' if playing else ' · 정지됨')) for key, source, playing, supported in rows if supported]
        if existing != wanted:
            self.targets.clear()
            for key, label in wanted:
                self.targets.addItem(label, key)
            index = self.targets.findData(selected)
            if index >= 0:
                self.targets.setCurrentIndex(index)
        phase = state['phase']
        active = phase not in ('OFF', 'DONE', 'FAULT')
        for control in (self.targets, self.idle, self.limit, self.start, self.quick):
            control.setEnabled(not active)
        self.undo.setEnabled(phase in ('WARNING', 'GRACE'))
        self.stop.setEnabled(active)
        self.status.setText(PHASE[phase] + (f" · {state['remaining']}초" if phase in ('WARNING', 'GRACE') else ''))
        self.detail.setText(state['reason'] or ('보호가 켜져 있습니다. 창을 닫아도 계속 동작합니다.' if active else '먼저 재생을 시작한 뒤 앱과 보호 시간을 선택해 주세요.'))
        if phase == 'WARNING' and self.last_phase != phase:
            self.reveal()  # Silent, visible cancellation opportunity; never force foreground focus.
        self.last_phase = phase
        self.tray.setToolTip('SleepSense · ' + PHASE[phase])

    def continue_play(self):
        self.worker.send('UNDO' if self.last_phase == 'GRACE' else 'CONTINUE')

    def reveal(self):
        self.showNormal()
        self.raise_()

    def closeEvent(self, event):
        if self.exiting or not self.tray.isVisible():
            self.quit_app()
            event.accept()
        else:
            self.hide()
            event.ignore()

    def quit_app(self):
        self.exiting = True
        self.worker.send('EXIT')
        self.tray.hide()
        # Never destroy a running QThread. Keep the event loop alive until timeout-bounded I/O ends.
        if self.worker.isRunning():
            self.worker.finished.connect(QApplication.instance().quit)
            self.hide()
        else:
            QApplication.instance().quit()


def main():
    app = QApplication(sys.argv)
    font_path = Path(os.environ.get('WINDIR', 'C:/Windows')) / 'Fonts' / 'malgun.ttf'
    if font_path.exists():
        QFontDatabase.addApplicationFont(str(font_path))
    app.setQuitOnLastWindowClosed(False)
    app.setApplicationName('SleepSense')
    # One instance per logged-in user; abandoned mutexes are released by Windows.
    import ctypes
    ctypes.windll.kernel32.CreateMutexW.restype = ctypes.c_void_p
    handle = ctypes.windll.kernel32.CreateMutexW(None, False, 'Local\\SleepSense.Windows.NightAlpha')
    if ctypes.windll.kernel32.GetLastError() == 183:
        QMessageBox.information(None, 'SleepSense', '이미 실행 중입니다. 작업 표시줄의 숨겨진 아이콘에서 열어 주세요.')
        return 0
    integration = '--integration-smoke-test' in sys.argv
    smoke = '--smoke-test' in sys.argv
    window = Window(smoke)
    window.show()
    if integration:
        destination = Path(os.environ.get('SLEEPSENSE_SMOKE_OUTPUT', '.'))
        destination.mkdir(parents=True, exist_ok=True)
        completed = False
        def backend_capture(state):
            nonlocal completed
            if completed:
                return
            completed = True
            import json
            (destination / 'windows-integration.json').write_text(json.dumps({
                'connected': state['phase'] != 'FAULT', 'session_count': len(state['rows']),
                'phase': state['phase'], 'reason': state['reason'], 'commands_sent': 0
            }, ensure_ascii=False), encoding='utf-8')
            window.grab().save(str(destination / 'windows-ui.png'))
            window.quit_app()
        window.worker.update.connect(backend_capture)
        QTimer.singleShot(15000, window.quit_app)
    if smoke:
        destination = Path(os.environ.get('SLEEPSENSE_SMOKE_OUTPUT', '.'))
        destination.mkdir(parents=True, exist_ok=True)
        def capture():
            window.grab().save(str(destination / 'windows-ui.png'))
            (destination / 'windows-smoke.txt').write_text('UI initialized; no media commands; no sensors opened.\n', encoding='utf-8')
            app.quit()
        QTimer.singleShot(500, capture)
    result = app.exec()
    ctypes.windll.kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
    ctypes.windll.kernel32.CloseHandle(handle)
    return result


if __name__ == '__main__':
    sys.exit(main())
