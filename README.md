# SmartPause Android App

Android application for intelligent screen time management using reinforcement learning.

## What It Does

- **App Usage Tracking** — Monitors time spent on selected apps in the background
- **Smart Interventions** — Sends vibration alerts when overuse is detected
- **Personalized Learning** — Uses Q-Learning to adapt intervention timing to user behavior
- **Daily Data Sync** — Uploads usage data at 3 AM daily for model training
- **Baseline Detection** — Learns user's normal usage patterns before intervening

## Features

- Select specific apps to monitor during registration
- Foreground service for continuous background tracking
- On-device Q-table inference for real-time decisions
- A/B testing support (test vs production mode)
- Admin console for debugging and manual data upload

## Tech Stack

- **Language**: Java
- **Min SDK**: Android 8.0 (API 26)
- **Networking**: Retrofit + Gson
- **Background**: Foreground Service + AlarmManager
- **Permissions**: Usage Stats, Notifications, Battery Optimization Exemption

## Required Permissions

| Permission | Purpose |
|------------|---------|
| Usage Access | Track app usage statistics |
| Notifications | Show persistent service notification |
| Battery Optimization Exemption | Keep service running reliably |

## Key Components

| Component | Purpose |
|-----------|---------|
| `UsageTrackingService` | Background service for usage monitoring |
| `UploadAlarmReceiver` | Handles scheduled 3 AM data uploads |
| `RegistrationActivity` | User onboarding and app selection |
| `MainActivity` | Dashboard showing usage stats |
| `AdminConsoleActivity` | Debug tools and manual controls |
