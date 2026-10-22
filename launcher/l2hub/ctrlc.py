"""Посылает Ctrl+C консольному процессу: JVM на это выполняет shutdown-хуки и корректно завершается.

Запускается отдельным процессом (servers.graceful_stop), потому что отцепляется от своей консоли
и цепляется к консоли цели. Без импортов пакета — запускается как скрипт.
Коды выхода: 0 — отправлено, 2 — не удалось подключиться к консоли цели, 3 — не удалось отправить.
"""

import ctypes
import sys

kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

target = int(sys.argv[1])
kernel32.FreeConsole()
if not kernel32.AttachConsole(target):
    sys.exit(2)
# Сами Ctrl+C игнорируем, иначе событие завершит и этот процесс.
kernel32.SetConsoleCtrlHandler(None, True)
if not kernel32.GenerateConsoleCtrlEvent(0, 0):
    sys.exit(3)
sys.exit(0)
