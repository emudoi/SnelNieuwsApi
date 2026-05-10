"""SnelNieuws push notifications — manual triggers only.

Three DAGs, all unscheduled:

  - snelnieuws_notifications_prod_manual    → fan-out to iOS (production
        APNs) + Android (FCM) in parallel. One trigger, both platforms.
        iOS path: POST /notifications/dispatch — App Store + TestFlight
        builds whose tokens work against api.push.apple.com.
        Android path: POST /android/notifications/dispatch — every Android
        FCM subscriber (FCM has no sandbox/prod split).

  - snelnieuws_notifications_sandbox_manual → POST /notifications/dispatch-sandbox
        iOS-only. Sends to subscribers whose apns_environment='sandbox'
        (Xcode-debug installs that only work against
        api.sandbox.push.apple.com). FCM has no sandbox concept, so there
        is no Android counterpart on this DAG.

  - snelnieuws_notifications_broadcast_manual → fan-out to iOS broadcast
        + Android broadcast in parallel. Each side has its own feature
        flag in `feature_flags`:
          test_notification       → iOS sandbox
          notify_applestore_apps  → iOS production
          notify_android          → all Android subscribers
        Flip rows in psql to enable/disable per side without redeploying.

The user's requirement: "the trigger from the airflow should call two
functions parallel to start notifying both the platform". The two HTTP
calls are independent Airflow tasks with no upstream dependency, so they
run concurrently and one platform failing does not block the other.

Idempotency: each dispatch endpoint records every call in its own table
(notification_dispatches for iOS, android_notification_dispatches for
Android) and bumps the per-tier `as_of_article_id`. A retry-after-success
is a safe no-op (count = 0).

Endpoint URLs are hardcoded — they're not secrets and don't vary across
environments. The shared X-API-Key is read from an Airflow Variable
(populated from Vault by emudoi-service-infra's vault-publish role):

  - `snelnieuws_notifications_api_key` → shared secret in X-API-Key header
"""
import pendulum
import requests
from airflow.decorators import dag, task
from airflow.models import Variable
from airflow.models.param import Param

PROD_ENDPOINT_IOS         = "https://api.snel.emudoi.com/notifications/dispatch"
SANDBOX_ENDPOINT_IOS      = "https://api.snel.emudoi.com/notifications/dispatch-sandbox"
BROADCAST_ENDPOINT_IOS    = "https://api.snel.emudoi.com/notifications/broadcast"
DISPATCH_ENDPOINT_ANDROID = "https://api.snel.emudoi.com/android/notifications/dispatch"
BROADCAST_ENDPOINT_ANDROID = "https://api.snel.emudoi.com/android/notifications/broadcast"

DISPATCH_TIMEOUT_S = 60


def _post_dispatch(endpoint: str, frequency: str) -> dict:
    api_key = Variable.get("snelnieuws_notifications_api_key")
    params = {"frequency": int(frequency)} if frequency else {}
    r = requests.post(
        endpoint,
        params=params,
        headers={"X-API-Key": api_key},
        timeout=DISPATCH_TIMEOUT_S,
    )
    r.raise_for_status()
    return r.json()


def _post_broadcast(endpoint: str, text: str) -> dict:
    api_key = Variable.get("snelnieuws_notifications_api_key")
    r = requests.post(
        endpoint,
        json={"text": text},
        headers={
            "X-API-Key": api_key,
            "Content-Type": "application/json",
        },
        timeout=DISPATCH_TIMEOUT_S,
    )
    r.raise_for_status()
    return r.json()


@dag(
    dag_id="snelnieuws_notifications_prod_manual",
    description=(
        "Manually dispatch SnelNieuws push notifications. Fans out to iOS "
        "(production APNs) and Android (FCM) in parallel — one trigger, "
        "both platforms."
    ),
    schedule=None,
    start_date=pendulum.datetime(2026, 5, 1, tz="Europe/Amsterdam"),
    catchup=False,
    max_active_runs=1,
    params={
        "frequency": Param(
            default="",
            type=["string"],
            enum=["", "1", "2", "3", "4"],
            description="Frequency tier (1-4) or empty to dispatch every tier.",
        ),
    },
    tags=["snelnieuws", "notifications", "manual"],
)
def snelnieuws_notifications_prod_manual():
    @task
    def dispatch_ios(**context) -> dict:
        freq = (context["params"].get("frequency") or "").strip()
        return _post_dispatch(PROD_ENDPOINT_IOS, freq)

    @task(trigger_rule="all_done")
    def dispatch_android(**context) -> dict:
        freq = (context["params"].get("frequency") or "").strip()
        return _post_dispatch(DISPATCH_ENDPOINT_ANDROID, freq)

    # No upstream dependency between the two — they run in parallel. The
    # Android task uses trigger_rule="all_done" so an iOS failure (e.g.
    # APNs 5xx) does not skip Android. Each task fails independently.
    dispatch_ios()
    dispatch_android()


@dag(
    dag_id="snelnieuws_notifications_sandbox_manual",
    description=(
        "Manually dispatch iOS-sandbox push notifications (Xcode-debug "
        "installs). FCM has no sandbox split, so this DAG is iOS-only."
    ),
    schedule=None,
    start_date=pendulum.datetime(2026, 5, 1, tz="Europe/Amsterdam"),
    catchup=False,
    max_active_runs=1,
    params={
        "frequency": Param(
            default="",
            type=["string"],
            enum=["", "1", "2", "3", "4"],
            description="Frequency tier (1-4) or empty to dispatch every tier.",
        ),
    },
    tags=["snelnieuws", "notifications", "manual"],
)
def snelnieuws_notifications_sandbox_manual():
    @task
    def dispatch(**context) -> dict:
        freq = (context["params"].get("frequency") or "").strip()
        return _post_dispatch(SANDBOX_ENDPOINT_IOS, freq)

    dispatch()


@dag(
    dag_id="snelnieuws_notifications_broadcast_manual",
    description=(
        "Send a free-form push to whichever platform/environment is enabled "
        "in feature_flags. Fans out iOS and Android broadcasts in parallel."
    ),
    schedule=None,
    start_date=pendulum.datetime(2026, 5, 1, tz="Europe/Amsterdam"),
    catchup=False,
    max_active_runs=1,
    params={
        "text": Param(
            default="",
            type="string",
            minLength=1,
            description="The message body for the push (alert title is hardcoded to 'Snel Nieuws').",
        ),
    },
    tags=["snelnieuws", "notifications", "manual", "broadcast"],
)
def snelnieuws_notifications_broadcast_manual():
    @task
    def broadcast_ios(**context) -> dict:
        text = (context["params"].get("text") or "").strip()
        if not text:
            raise ValueError("text is required")
        return _post_broadcast(BROADCAST_ENDPOINT_IOS, text)

    @task(trigger_rule="all_done")
    def broadcast_android(**context) -> dict:
        text = (context["params"].get("text") or "").strip()
        if not text:
            raise ValueError("text is required")
        return _post_broadcast(BROADCAST_ENDPOINT_ANDROID, text)

    broadcast_ios()
    broadcast_android()


# Instantiate so Airflow's DagBag scanner picks each DAG up.
snelnieuws_notifications_prod_manual()
snelnieuws_notifications_sandbox_manual()
snelnieuws_notifications_broadcast_manual()
