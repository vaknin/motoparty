import json
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[3]
FIXTURES = REPO / "fixtures"


def load_fixture(rel: str):
    return json.loads((FIXTURES / rel).read_text(encoding="utf-8"))


@pytest.fixture
def fixture():
    return load_fixture
