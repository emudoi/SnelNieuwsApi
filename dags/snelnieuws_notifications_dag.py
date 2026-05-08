"""SnelNieuws push notifications — manual triggers only.

Two DAGs, both unscheduled:

  - snelnieuws_notifications_prod_manual    → POST /notifications/dispatch
        Sends to subscribers whose apns_environment='production' (App Store
        + TestFlight builds — tokens that work against api.push.apple.com).

  - snelnieuws_notifications_sandbox_manual → POST /notifications/dispatch-sandbox
        Sends to subscribers whose apns_environment='sandbox' (Xcode-debug
        installs — tokens that only work against api.sandbox.push.apple.com).

Both are triggered manually from the Airflow UI. Each takes a `frequency`
DAG Param (1-4, or empty for "all tiers"). The API decides what message
to send (counts new articles since the last dispatch for that tier and
pushes "X new articles" via APNs).

Idempotency: the dispatch endpoint records every call in
`notification_dispatches` and bumps the per-tier `as_of_article_id`. A
retry-after-success is therefore a safe no-op (count = 0).

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

PROD_ENDPOINT    = "https://api.snel.v1.emudoi.com/notifications/dispatch"
SANDBOX_ENDPOINT = "https://api.snel.v1.emudoi.com/notifications/dispatch-sandbox"

DISPATCH_TIMEOUT_S = 60


def _make_manual_dag(dag_id: str, endpoint: str, description: str):
    @dag(
        dag_id=dag_id,
        description=description,
        schedule=None,  # manual triggers only
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
    def _dag():
        @task
        def dispatch(**context) -> dict:
            api_key = Variable.get("snelnieuws_notifications_api_key")
            freq = (context["params"].get("frequency") or "").strip()
            params = {"frequency": int(freq)} if freq else {}
            r = requests.post(
                endpoint,
                params=params,
                headers={"X-API-Key": api_key},
                timeout=DISPATCH_TIMEOUT_S,
            )
            r.raise_for_status()
            return r.json()

        dispatch()

    return _dag()


# Bind to module globals so Airflow's DagBag scanner finds each DAG —
# the @dag-decorated factory returns a DAG object, but a bare function
# call would otherwise discard it.
globals()["snelnieuws_notifications_prod_manual"] = _make_manual_dag(
    "snelnieuws_notifications_prod_manual",
    PROD_ENDPOINT,
    "Manually dispatch SnelNieuws push notifications to PRODUCTION subscribers (App Store + TestFlight).",
)
globals()["snelnieuws_notifications_sandbox_manual"] = _make_manual_dag(
    "snelnieuws_notifications_sandbox_manual",
    SANDBOX_ENDPOINT,
    "Manually dispatch SnelNieuws push notifications to SANDBOX subscribers (Xcode-debug installs).",
)
