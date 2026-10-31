"""Web research: search result parsing, page text, the fallback to Bing, and the fact sheet."""
import asyncio
import base64

import httpx

from cj import lookups, scout

DDG = """
<div class="result"><a rel="nofollow" class="result__a" href="https://www.planetf1.com/results/us-gp">US Grand Prix
<b>2026</b> results</a><a class="result__snippet" href="x">Kimi Antonelli <b>won</b> in Austin.</a></div>
<div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.bbc.co.uk%2Fsport%2Ff1&amp;rut=1">BBC F1</a>
<a class="result__snippet" href="y">Latest results.</a></div>
<div class="result"><a class="result__a" href="https://duckduckgo.com/y.js?ad_provider=1">An ad</a></div>
"""

PAGE = """<html><head><style>.x{}</style><script>var tracking = 1;</script></head><body>
<nav>Home | News | Results | A very long menu entry that should still be dropped</nav>
<p>Kimi Antonelli won the United States Grand Prix with a lights-to-flag drive.</p>
<p>Short line</p><footer>Copyright and cookie settings for the whole website go here</footer></body></html>"""


def bing_link(url: str) -> str:
    u = "a1" + base64.urlsafe_b64encode(url.encode()).decode().rstrip("=")
    return f"https://www.bing.com/ck/a?!&amp;&amp;p=abc&amp;u={u}&amp;ntb=1"


BING = f"""<ol><li class="b_algo"><h2><a href="{bing_link('https://www.formula1.com/en/results')}">
<strong>F1</strong> Results</a></h2><div><p>Official results for every race.</p></div></li></ol>"""


def test_duckduckgo_results_are_parsed_and_ads_dropped():
    found = scout.parse_duckduckgo(DDG)
    assert [r["url"] for r in found] == ["https://www.planetf1.com/results/us-gp", "https://www.bbc.co.uk/sport/f1"]
    assert found[0]["title"] == "US Grand Prix\n2026 results" and found[0]["snippet"] == "Kimi Antonelli won in Austin."


def test_bing_tracking_links_are_unwrapped():
    found = scout.parse_bing(BING)
    assert found == [{"url": "https://www.formula1.com/en/results", "title": "F1 Results",
                      "snippet": "Official results for every race."}]


def test_page_text_keeps_sentences_and_drops_menus_scripts_and_footers():
    text = scout.page_text(PAGE)
    assert text == "Kimi Antonelli won the United States Grand Prix with a lights-to-flag drive."


def fake_web(monkeypatch, handler):
    real = httpx.AsyncClient
    monkeypatch.setattr(scout.httpx, "AsyncClient", lambda **kw: real(transport=httpx.MockTransport(handler), **kw))


def test_research_reads_the_top_pages(monkeypatch):
    def handler(request):
        if request.url.host == "html.duckduckgo.com":
            return httpx.Response(200, text=DDG)
        if request.url.host == "www.planetf1.com":
            return httpx.Response(200, text=PAGE, headers={"content-type": "text/html"})
        return httpx.Response(404)
    fake_web(monkeypatch, handler)
    sheet = asyncio.run(scout.research("who won the US grand prix"))
    assert sheet.startswith('Web search for "who won the US grand prix"')
    assert "[1] US Grand Prix\n2026 results (planetf1.com)" in sheet
    assert "lights-to-flag drive" in sheet and "[2] BBC F1 (bbc.co.uk)" in sheet


def test_research_falls_back_to_bing(monkeypatch):
    def handler(request):
        if request.url.host == "html.duckduckgo.com":
            return httpx.Response(202, text="<html>anomaly</html>")
        if request.url.host == "www.bing.com":
            return httpx.Response(200, text=BING)
        return httpx.Response(200, text=PAGE, headers={"content-type": "text/html"})
    fake_web(monkeypatch, handler)
    assert "[1] F1 Results (formula1.com)" in asyncio.run(scout.research("f1 results"))


def test_a_failed_search_is_reported_not_raised(monkeypatch):
    fake_web(monkeypatch, lambda request: httpx.Response(503))
    result = asyncio.run(lookups.run("web", {"query": "f1 results"}))
    assert result.startswith("LOOKUP FAILED: web search failed (DuckDuckGo answered 503; Bing answered 503)")


def test_web_is_a_lookup_and_is_cached(monkeypatch):
    calls = []

    async def fake_research(query):
        calls.append(query)
        return f"sheet for {query}"
    monkeypatch.setattr(scout, "research", fake_research)
    assert "web" in lookups.LOOKUPS
    assert asyncio.run(lookups.run("web", {"query": "Who won?"})) == "sheet for Who won?"
    assert asyncio.run(lookups.run("web", {"query": "who won?"})) == "sheet for Who won?"
    assert calls == ["Who won?"]
