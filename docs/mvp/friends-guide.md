# Stride for friends — the solo Garmin MVP

Thanks for trying Stride! It's a small, **local-only** running app: it reads your
runs and health data from **your own Garmin account**, works out your training
load, fitness, fatigue and form, and suggests what to run next. There is no
Stride account and no server — everything stays on your phone.

## Install
Follow [Installing Stride on Android](../release/install-android.md). The short
version: install **Obtainium**, add `https://github.com/jaypetez/stride` with the
tag filter `^android-v`, and tap **Install**. Obtainium keeps you updated.

## Try it without Garmin: demo mode
On the last onboarding step tap **Try demo**. Stride loads six months of
**synthetic** runs (not a real person's data) and computes everything exactly as
it would for you. A "Demo mode" banner stays at the top; tap **Exit demo** to
delete the demo data.

## Connect your Garmin account
Stride uses Garmin's free **Personal API**, which needs a key that only you can create:
1. On a computer, sign in to **Garmin Connect** on the web and open your account
   settings → **Personal API** (the Connect screen in Stride has a button that
   opens it).
2. Create a key and choose how long it lasts: 30, 90 or 180 days, or 1 year.
   **Garmin shows the key only once** — copy it.
3. In Stride, open **Connect Garmin**, paste the key, set the expiry date, and tap
   **Connect**. Stride checks the key with Garmin before saving it.
4. The first sync imports up to 180 days of history in the background.

Your key is encrypted on the phone and only ever sent to Garmin. Stride warns you
two weeks before it expires; then create a new key and connect again.

## What you'll see
- **Today:** your form (TSB) and what it means, fitness (CTL) and fatigue (ATL),
  your last run, this week vs last week, sleep / HRV / resting HR, and a
  suggested next workout with the reason behind it.
- **Runs:** every run with splits, heart-rate and pace charts, time in zones,
  training load and efficiency.
- **Fitness:** the fitness/fatigue/form chart, acute:chronic load ratio, your
  80/20 easy-vs-hard balance, an estimated VO2max (VDOT) trend and race-time
  predictions. These are **estimates**.
- **Plan:** an optional training plan toward a race, checked by safety guardrails.
- **AI coach (optional, off by default):** explains a run or your trend in plain
  language using your own Anthropic API key. It only ever sends computed numbers,
  never your raw data. Garmin's developer terms may restrict AI use of Garmin
  data, so it stays off unless you turn it on.

Stride is not medical advice. If anything in the health screening applies to you,
talk to a doctor before increasing your training.

## Delete your data
- **You → Garmin → Disconnect** removes your key (and optionally all synced data).
- **You → Delete all data** wipes everything Stride stored on the phone.
- Uninstalling the app also removes everything.

## Feedback
Tell Jayson what worked, what confused you and what you'd want next — or open an
issue at https://github.com/jaypetez/stride/issues.
