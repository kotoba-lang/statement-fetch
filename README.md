# kotoba-lang/statement-fetch

**Declarative retrieval of bank / brokerage statements through a browser,
where the agent structurally cannot type your password.**

Written for the recurring case where a non-Japanese institution (US bank
onboarding, KYC, visa/immigration paperwork) asks for "a personal bank
statement from the last 90 days" and a Japanese online bank will only hand one
over through a logged-in web UI.

```
resources/institutions/*.edn   what to click, per institution — data, not code
src/kotoba/statement_fetch.cljc      pure: validate a flow, compile it to argv
src/kotoba/statement_fetch/
  agent_browser.cljs                 nbb driver: spawn agent-browser, poll, report
bin/statement_fetch.cljs             CLI
```

## The safety property

An agent driving a banking session is one prompt away from being asked to type
a password. This library makes that impossible rather than discouraged:

- **Authentication is a `:handoff` step, and `:handoff` compiles to no argv.**
  There is no code path from a flow definition to a keystroke carrying a
  credential.
- **`validate-flow` rejects any step that would carry one** — by explicit key
  (`:step/password`, `:step/otp`, `:step/token`, …) and by selector/value
  matching authentication vocabulary in English and Japanese
  (`password`, `otp`, `パスワード`, `暗証`, `ワンタイム`, …).
- **`plan` refuses to compile an invalid flow at all**, so an unsafe flow never
  reaches a browser — it throws during compilation.
- **A flow may carry exactly one handoff, and it must be the login.** A
  mid-flow handoff is a validation error.

The operator authenticates in a real browser through their own password
manager. The driver watches for a completion marker (`ログアウト` appearing on
the page) and resumes on its own. It never reads, stores, autofills, or
transmits the credential.

`test/kotoba/statement_fetch_test.cljc` asserts each of these, and
`test/kotoba/institutions_test.cljs` re-asserts them against every institution
file actually shipped here — including that none contains a digit run long
enough to be an account number, since this repository is public.

## Institutions

| Flow | Language | Notes |
|---|---|---|
| `jp-paypay-bank.transaction-statement-en` | English | 英文 取引明細書, immediate PDF. 880 JPY/issuance per PayPay's current published fee table. |
| `jp-rakuten-bank.transaction-detail-ja` | Japanese | 入出金明細, immediate PDF, 24-month window, ≤3,000 entries. |

Rakuten has no `*-en` flow on purpose: its English documents are phone-request
only and arrive by post in 1–10 days, so no browser flow can produce one.

## Verified vs unverified

A flow starts life with `:step/unverified true` on every selector nobody has
run yet, and **`fetch` refuses to execute an unverified flow**. A wrong
selector on a banking page is not a harmless miss.

Promoting a flow to verified:

```bash
nbb --classpath src:bin:resources bin/statement_fetch.cljs verify <flow>
# authenticate in the browser yourself, then:
nbb --classpath src:bin:resources bin/statement_fetch.cljs discover --profile Default
# replace the guessed selectors in resources/institutions/<flow>.edn, drop
# :step/unverified, and re-run verify
```

As of 2026-07-25 both shipped flows are **unverified past the login step**.
What *is* verified is PayPay's personal login entry point
(`https://login.paypay-bank.co.jp/wctx/LoginAction.do`), extracted from
`Janet_Login_URL` — the site's visible ログイン links are `javascript:void(0)`
and resolve through `jnb_tologin()`, so the URL is not scrapeable from markup.

## Usage

```bash
nbb --classpath src:bin:resources bin/statement_fetch.cljs list
nbb --classpath src:bin:resources bin/statement_fetch.cljs verify jp-paypay-bank.transaction-statement-en
nbb --classpath src:bin:resources bin/statement_fetch.cljs plan  jp-paypay-bank.transaction-statement-en \
  --from 2026-04-26 --to 2026-07-25 --out ./statement.pdf
nbb --classpath src:bin:resources bin/statement_fetch.cljs fetch jp-paypay-bank.transaction-statement-en \
  --from 2026-04-26 --to 2026-07-25 --out ./statement.pdf --profile Default
```

`--profile <name>` reuses an existing Chrome profile (`agent-browser profiles`
lists them), so the operator's own session and password manager are what the
flow runs against.

## Requirements

- [`nbb`](https://github.com/babashka/nbb)
- [`agent-browser`](https://www.npmjs.com/package/agent-browser) installed
  globally. The driver resolves the package entry point directly rather than
  the `agent-browser` shim on PATH — observed 2026-07-25, npm's
  `/opt/homebrew/bin/agent-browser` symlink pointed into a deleted worktree
  while the package itself was fine.

## Tests

```bash
nbb --classpath src:test:resources test/run_tests.cljs
```

## Adding an institution

Add one EDN file to `resources/institutions/`. No code changes — the pure core
validates and compiles whatever you drop there, and `institutions_test.cljs`
picks it up automatically. Keep the login a `:handoff`; validation will not let
you do otherwise.
