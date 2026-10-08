import json, subprocess, sys, pathlib

TOOL = pathlib.Path(__file__).resolve().parents[1] / "har_analyzer.py"
PW, USER, TOKEN, COOKIE = "S3cr3tPassw0rd!", "student123", "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6", "JSESSIONID=ZZZcookieVALUE999"


def har():
    def h(n, v): return {"name": n, "value": v}
    return {"log": {"entries": [
        {"request": {"method": "GET", "url": "http://172.16.16.16/", "headers": []},
         "response": {"status": 200, "headers": [h("Set-Cookie", COOKIE)], "content": {"text": "<html>login</html>"}}},
        {"request": {"method": "POST", "url": "http://172.16.16.16/userlogin.php?t=" + TOKEN,
                     "headers": [h("Cookie", COOKIE), h("Content-Type", "application/x-www-form-urlencoded"),
                                 h("Referer", "http://172.16.16.16/?tok=" + TOKEN), h("Authorization", "Basic " + TOKEN)],
                     "postData": {"mimeType": "application/x-www-form-urlencoded",
                                  "params": [{"name": "username", "value": USER}, {"name": "password", "value": PW},
                                             {"name": "mode", "value": "191"}, {"name": "csrf", "value": TOKEN},
                                             {"name": "a", "value": "1700000000000"}]}},
         "response": {"status": 200, "headers": [h("Set-Cookie", COOKIE)],
                      "content": {"mimeType": "text/html", "text": "You are now connected. Welcome " + USER}}},
        {"request": {"method": "GET", "url": "http://172.16.16.16/keepalive?x=1", "headers": []},
         "response": {"status": 200, "headers": [], "content": {}}},
    ]}}


def test_redaction(tmp_path):
    p = tmp_path / "c.har"; p.write_text(json.dumps(har()))
    out = tmp_path / "o.json"
    r = subprocess.run([sys.executable, "-I", str(TOOL), str(p), "-o", str(out)], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    text = out.read_text()
    for secret in (PW, TOKEN, COOKIE, "ZZZcookieVALUE999", USER):
        assert secret not in text
    d = json.loads(text)
    assert d["login"]["fields"]["password"] == "{{password}}"
    assert d["login"]["fields"]["username"] == "{{username}}"
    assert d["login"]["fields"]["mode"] == "191"
    assert d["login"]["fields"]["a"] == "{{timestamp_ms}}"
    assert "Cookie" not in d["login"]["headers"] and "Authorization" not in d["login"]["headers"]
    assert d["portal_host"] == "172.16.16.16"
    assert d["keepalive"]["path"] == "/keepalive"
    assert "connected" in d["_analysis"]["success_keywords_seen_in_response"]
