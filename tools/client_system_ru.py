"""Русский ввод в клиенте без чужих бинарников: копия system -> system_ru с языком Russia.

Сам код — launcher/l2hub/clientsys.py (лаунчер делает system_ru сам, когда в «Клиент и сеть» выбирают русский);
здесь — то же из консоли, с выбором базового языка и настоящими русскими текстами. Подробности — в описании модуля.

--ru-files <папка> — настоящие *-ru.dat из чистого русского клиента той же сборки (Иннова P268 для H5, тексты
Иннова для Essence 542): копируются в system_ru, и тексты берутся из них; каких-то нет — на их место кладётся
файл базового языка. --base Europe — для клиентов 4game EU (Classic, Essence).

Использование: python -X utf8 -I tools/client_system_ru.py "C:\\Games\\LA2 H5" [--ru-files DIR] [--base Europe]
Повторный запуск обновляет system_ru (localization.ini каждый раз собирается из system).
Откат: удалить system_ru (в Корзину); в профиле лаунчера client.exe — обратно system\\L2.exe.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "launcher"))
from l2hub.clientsys import make_system_ru  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(prog="client_system_ru")
    ap.add_argument("client", type=Path, help="папка клиента (в ней system)")
    ap.add_argument("--src", default="system")
    ap.add_argument("--dst", default="system_ru")
    ap.add_argument("--ru-files", type=Path, help="папка с настоящими *-ru.dat")
    ap.add_argument("--base", default="English", help="чьи тексты, шрифты и текстуры отдать [Russia]: English, у клиентов 4game EU — Europe")
    args = ap.parse_args()
    try:
        make_system_ru(args.client, args.src, args.dst, args.ru_files, args.base)
    except ValueError as e:
        sys.exit(str(e))
    return 0


if __name__ == "__main__":
    sys.exit(main())
