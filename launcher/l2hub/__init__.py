"""L2Hub — личный лаунчер локальных серверов Lineage II.

Версия: 0.<готовых хроник из списка 1.0>.<порядковый номер коммита внутри этой цифры>. Схема и список — docs/PLAN.md
(«Решения», 2026-10-18), история — CHANGELOG.md. Это единственное место версии: окно берёт её через Api.version().
"""

__version__ = "1.0.10"


def _system_certs() -> None:
    """Сертификаты https проверяет сама Windows (truststore), а не OpenSSL по локальному хранилищу.

    Windows держит корневые сертификаты не все сразу, а докачивает нужный, когда он впервые понадобится. OpenSSL
    видит только уже скачанные: на чистой машине gitlab.com (корень USERTrust RSA) и github.com (USERTrust ECC)
    падали с CERTIFICATE_VERIFY_FAILED «unable to get issuer certificate», пока пользователь не открывал сайт
    в браузере (отчёт пользователя на 1.0.0: не скачивались исходники Essence). Без truststore — как раньше."""
    try:
        import truststore  # noqa: PLC0415
    except ImportError:
        return
    truststore.inject_into_ssl()


_system_certs()
