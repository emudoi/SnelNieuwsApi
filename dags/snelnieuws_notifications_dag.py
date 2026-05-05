"""Trigger SnelNieuws push notifications for each frequency tier (1×–4× per day).

One DAG per tier. Each DAG fires `POST /notifications/dispatch?frequency=N`
on its own schedule. The API itself decides what message to send (counts new
articles since the last dispatch for that tier and pushes a generic
"X new articles" message via FCM).

Idempotency: the dispatch endpoint records every call in
`notification_dispatches` and bumps the per-tier `as_of_article_id`. A
retry-after-success is therefore a safe no-op (count = 0).

Variables (all read from Airflow Variables, populated from Vault by
`emudoi-service-infra`'s `vault-publish` role):
  - `snelnieuws_dispatch_endpoint`        → https://api.snel.v1.emudoi.com/notifications/dispatch
                                            (the `v1` subdomain is a temporary k3s-side
                                            convention; will move to api.snel.emudoi.com later)
  - `snelnieuws_notifications_api_key`    → shared secret sent in the X-API-Key header
"""
from datetime import timedelta

import pendulum
import requests
from airflow.decorators import dag, task
from airflow.models import Variable

DISPATCH_TIMEOUT_S = 60

# (frequency, dag_id, schedule, description)
# Schedules are in Europe/Amsterdam. Adjust the times to taste — the API
# does not care when these fire, only how often, since each tier tracks its
# own as_of_article_id.
TIERS = [
    (1, "snelnieuws_notifications_1x", "0 9 * * *",
     "Push notifications once per day to subscribers who chose 1×/day."),
    (2, "snelnieuws_notifications_2x", "0 9,18 * * *",
     "Push notifications twice per day to subscribers who chose 2×/day."),
    (3, "snelnieuws_notifications_3x", "0 9,13,18 * * *",
     "Push notifications three times per day to subscribers who chose 3×/day."),
    (4, "snelnieuws_notifications_4x", "0 8,12,16,20 * * *",
     "Push notifications four times per day to subscribers who chose 4×/day."),
]


def _make_dispatch_dag(frequency: int, dag_id: str, schedule: str, description: str):
    @dag(
        dag_id=dag_id,
        description=description,
        schedule=schedule,
        start_date=pendulum.datetime(2026, 5, 1, tz="Europe/Amsterdam"),
        catchup=False,
        max_active_runs=1,
        default_args={
            "owner": "emudoi",
            "retries": 1,
            "retry_delay": timedelta(minutes=5),
        },
        tags=["snelnieuws", "notifications", f"freq={frequency}"],
    )
    def _dag():
        @task
        def dispatch() -> dict:
            url = Variable.get("snelnieuws_dispatch_endpoint")
            api_key = Variable.get("snelnieuws_notifications_api_key")
            r = requests.post(
                url,
                params={"frequency": frequency},
                headers={"X-API-Key": api_key},
                timeout=DISPATCH_TIMEOUT_S,
            )
            r.raise_for_status()
            return r.json()

        dispatch()

    return _dag()


for freq, _dag_id, _schedule, _desc in TIERS:
    _make_dispatch_dag(freq, _dag_id, _schedule, _desc)
