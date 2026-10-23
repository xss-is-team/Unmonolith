# Test fixture provenance

- Upstream: `https://github.com/apache/commons-math`
- Pinned release tag: `MATH_3_6_1`
- Source commit: `16abfe5de688cc52fb0396e0609cb33044b15653`
- Local fixture: this directory; copied from `~/tmp/HACK/tmp-repos/commons-math-3.6.1` without its `.git` directory.
- Contents include the original Maven build descriptor, production source, and test source/resources. At setup there were 990 production `.java` files and 611 test `.java` files.

Use this copy for parser, AST/CFG, and complexity-analysis experiments. Preserve it as a fixed input; create a separate working copy for any transformation or test that edits source files. The original clone remains in `~/tmp/HACK/tmp-repos/commons-math-3.6.1`.
