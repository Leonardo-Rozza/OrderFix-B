# RFC 8785 / JCS official vectors

Source: cyberphone/json-canonicalization, testdata/{input,output},
commit 19d51d7fe467d4706a3ff08adf8a748f29fc21e0.

- Upstream: https://github.com/cyberphone/json-canonicalization/tree/19d51d7fe467d4706a3ff08adf8a748f29fc21e0/testdata
- Specification: https://www.rfc-editor.org/rfc/rfc8785.html
- License: Apache-2.0, copyright Anders Rundgren.

The repository text convention adds a terminal LF to every fixture. The test removes only that
added LF from upstream files that did not contain one, and then verifies these upstream SHA-256
checksums before using the vectors:

| File | Upstream SHA-256 |
| --- | --- |
| input/arrays.json | e503b6d71d1afa595b1c74b1016445c944cd89f90418066b23de1aeda7d17563 |
| input/french.json | 03676a951cd8753ac62589f72eb2105cc782c33425418cfe1d517c111f6e5d5a |
| input/structures.json | d66893805be1784116af50af3110d08766c70a6b4aad93374723f72346e7aaa6 |
| input/unicode.json | 4621864e014d4a805a563f55b9ea20aba4a2d2dc09c7394f625496998c00702c |
| input/values.json | c4a041b503d6bc236036ef44db4dac499272f60fc22c40dc3b7a54870ba6f1c3 |
| input/weird.json | a3a905266bd4a49a969274ea69baa14ee0c4af0ead926d6fa2b7612b4af75387 |
| output/arrays.json | 099601b171cafed97c333f8878d68e7f8c8f795412adb34b2fdcf0e7c7beac42 |
| output/french.json | d99d0ebdcb0033cb858cfa830ae46bc0fb3309413b271f1da828c89901a27ed5 |
| output/structures.json | 605f65004ec2db7692522a0852c22f1c989e036d547e88963d1a3143cf3195d5 |
| output/unicode.json | 0d99aad92a125196ff887876643fd3206786a84ddce2cee52ba4ad256d2381d3 |
| output/values.json | 2d5e01a318d0f0879ab568c4be289c8b1f64ef8921a53c6277d5e069978baacb |
| output/weird.json | 6af595a9aa80110b964b4de3f82a05fa6ae7423005019bacfa2620dddc4e94d1 |

arrays, french, structures, and values pass OrdenFix's strict I-JSON gate and are compared
byte-for-byte after JCS. unicode intentionally contains non-NFC text and weird contains CR;
OrdenFix rejects both before JCS because its legal-manifest contract is stricter than the generic
upstream corpus.
