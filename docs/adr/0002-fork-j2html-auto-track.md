# Fork J2HTML with auto-tracked render

Plain J2HTML cannot re-render on signal change and annotation processing would break any-IDE pure-Java + hot-reload. We shade/fork tags to add with* builders plus onClick/onChange lambdas, and auto-track signal.get() during render() for server diffing, keeping params as children-only.
