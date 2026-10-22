# Сборка стандалона L2Hub (docs/RELEASE.md, «Программа и установщик»): PyInstaller onedir, два exe в одной папке —
# оконный L2Hub.exe и консольный l2hub-cli.exe (служебные процессы окна и cli). Запуск — packaging/build.py.
import os

from PyInstaller.utils.hooks import collect_submodules

HERE = os.path.abspath(SPECPATH)
LAUNCHER = os.path.dirname(HERE)
PKG = os.path.join(LAUNCHER, "l2hub")
UI = os.path.join(PKG, "ui")

# Всё, что в пакете не Python (интерфейс, chronicles.json, js-помощники L2Solo и их данные), — кроме подложки карты
# ui/maps: это картинки из клиента NCSoft, только локально (docs/RELEASE.md). Новые файлы попадают сами.
datas = []
for top, dirs, files in os.walk(PKG):
    dirs[:] = [d for d in dirs if d != "__pycache__" and not (top == UI and d == "maps")]
    for f in files:
        if not f.endswith((".py", ".pyc")):
            datas.append((os.path.join(top, f), os.path.normpath(os.path.join("l2hub", os.path.relpath(top, PKG)))))

# Модули пакета грузятся и лениво (tray), и через runpy (brain, ctrlc в l2hub-cli) — собрать все.
hidden = collect_submodules("l2hub")
icon = os.path.join(UI, "assets", "icon.ico")


def analysis(entry):
    return Analysis([os.path.join(HERE, entry)], pathex=[LAUNCHER], datas=datas, hiddenimports=hidden,
                    excludes=["tkinter", "unittest", "pydoc_data"], noarchive=False)


gui = analysis("entry_gui.py")
cli = analysis("entry_cli.py")

gui_exe = EXE(PYZ(gui.pure), gui.scripts, [], exclude_binaries=True, name="L2Hub", icon=icon, console=False,
              upx=False, contents_directory="_internal")
cli_exe = EXE(PYZ(cli.pure), cli.scripts, [], exclude_binaries=True, name="l2hub-cli", icon=icon, console=True,
              upx=False, contents_directory="_internal")

COLLECT(gui_exe, gui.binaries, gui.datas, cli_exe, cli.binaries, cli.datas, name="L2Hub", upx=False)
