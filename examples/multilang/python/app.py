def unsafe(name: str | None):
    return name.count("a")


def guarded(name: str | None):
    if name is None:
        return 0
    return name.count("a")
