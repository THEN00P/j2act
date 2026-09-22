# Fork J2HTML with auto-tracked render

Plain J2HTML cannot re-render on signal change and annotation processing would break any-IDE pure-Java + hot-reload. We shade/fork tags to add with* builders plus onClick/onChange lambdas, and auto-track signal.get() during render() for server diffing, keeping params as children-only.

The fork became generation: the tag API is generated from HTML data in j2html's shape rather than forked from j2html's source (ADR 0021).
