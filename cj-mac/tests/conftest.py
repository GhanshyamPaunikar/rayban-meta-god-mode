import pytest

from cj import lookups


@pytest.fixture(autouse=True)
def fresh_lookups():
    """Each test fetches its own (fake) lookups instead of reusing another test's cached answer."""
    lookups._cache.clear()
