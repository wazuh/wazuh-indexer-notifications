## [v5.0.0]

### Added
- Initialize `wazuh-indexer-notifications` repository [(#2)](https://github.com/wazuh/wazuh-indexer-notifications/issues/2) [(#1335)](https://github.com/wazuh/wazuh-indexer/issues/1335)
- Add the Active Response notification channel [(#6)](https://github.com/wazuh/wazuh-indexer-notifications/issues/6)
- Index Active Response documents in batches [(#41)](https://github.com/wazuh/wazuh-indexer-notifications/issues/41)
- Move default notification channels to the Indexer [(#45)](https://github.com/wazuh/wazuh-indexer-notifications/issues/45)
- Copy the full `wazuh` object into Active Response events [(#101)](https://github.com/wazuh/wazuh-indexer-notifications/issues/101)
- Add settings to limit notification configurations, groups, senders and active responses [(#1276)](https://github.com/wazuh/wazuh-indexer-plugins/issues/1276) [(#1420)](https://github.com/wazuh/wazuh-indexer-plugins/issues/1420)

### Changed
- Block webhook channels from reaching loopback, private and cloud-metadata addresses by default [(#1853)](https://github.com/wazuh/wazuh-indexer/issues/1853)
- (operational) Resolve the build version from `VERSION.json` [(#1595)](https://github.com/wazuh/wazuh-indexer-plugins/issues/1595)

### Removed

### Fixed
- Refuse active responses whose `local` location has no target agent [(#183)](https://github.com/wazuh/wazuh-indexer-notifications/issues/183)
- Prevent the notification configuration limit from being exceeded by concurrent requests [(#194)](https://github.com/wazuh/wazuh-indexer-notifications/issues/194)
- (operational) Fix the CodeQL workflow settings [(#175)](https://github.com/wazuh/wazuh-indexer-notifications/pull/175)

## Prior versions
- []()
