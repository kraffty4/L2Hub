"""l2hub-cli.exe — консольный вход собранного L2Hub: служебные процессы окна и cli.

  l2hub-cli ctrlc <pid>   — Ctrl+C в консоль сервера (servers.graceful_stop, l2solo.stop)
  l2hub-cli brain <id> …  — мозги фейков (servers.start_brain)
  l2hub-cli <команда> …   — то же, что python -m l2hub.cli

Консольный, а не оконный: у оконного exe PyInstaller нет sys.stdout и sys.stderr, а мозги пишут в stderr.
Из окна служебные процессы запускаются с CREATE_NO_WINDOW — консоли не видно.
"""

import runpy
import sys

HELPERS = {"ctrlc": "l2hub.ctrlc", "brain": "l2hub.brain"}

if len(sys.argv) > 1 and sys.argv[1] in HELPERS:
    module = HELPERS[sys.argv[1]]
    sys.argv = [module, *sys.argv[2:]]
    runpy.run_module(module, run_name="__main__", alter_sys=True)
else:
    from l2hub.cli import main

    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
