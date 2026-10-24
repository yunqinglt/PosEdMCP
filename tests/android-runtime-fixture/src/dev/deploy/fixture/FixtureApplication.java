package dev.deploy.fixture;
public final class FixtureApplication extends android.app.Application {
 @Override public void onCreate() { super.onCreate(); Results.applicationValue = Sentinel.answer(); }
}