## [v5.0.0]

### Added
- Add the Active Response notification channel [(#6)](https://github.com/wazuh/wazuh-indexer-notifications/issues/6)
- Add batch indexing for Active Response documents [(#41)](https://github.com/wazuh/wazuh-indexer-notifications/issues/41)
- Add the default notification channels to the Indexer [(#45)](https://github.com/wazuh/wazuh-indexer-notifications/issues/45)
- Add the full `wazuh` object to Active Response events [(#101)](https://github.com/wazuh/wazuh-indexer-notifications/issues/101)
- Add settings to limit notification configurations, groups, senders and active responses [(#1276)](https://github.com/wazuh/wazuh-indexer-plugins/issues/1276) [(#1420)](https://github.com/wazuh/wazuh-indexer-plugins/issues/1420)

- Initialize `wazuh-indexer-notifications` repository [(#2)](https://github.com/wazuh/wazuh-indexer-notifications/issues/2) [(#1335)](https://github.com/wazuh/wazuh-indexer/issues/1335)

### Changed
- Block webhook channels from reaching loopback, private and cloud-metadata addresses by default [(#1853)](https://github.com/wazuh/wazuh-indexer/issues/1853)

- (operational) Resolve the build version from `VERSION.json` [(#1595)](https://github.com/wazuh/wazuh-indexer-plugins/issues/1595)

### Removed

### Fixed
- Fix active responses reported as delivered when the event has no target agent [(#183)](https://github.com/wazuh/wazuh-indexer-notifications/issues/183)
- Fix the notification configuration limit being exceeded by concurrent requests [(#194)](https://github.com/wazuh/wazuh-indexer-notifications/issues/194)

- (operational) Fix the CodeQL workflow settings [(#175)](https://github.com/wazuh/wazuh-indexer-notifications/pull/175)

## Prior versions
- []()
