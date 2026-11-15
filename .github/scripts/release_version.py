import argparse
import re


def release_version(tag):
    versions = re.findall(r"\d+\.\d+\.\d+(?:[a-zA-Z0-9-]+)?", tag)
    if not versions:
        raise ValueError(f"No version found in release tag: {tag}")
    return re.sub(r"(-?rc\d+|-SNAPSHOT|-snapshot-.*)$", "", versions[-1])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Extract the app version from a release tag.")
    parser.add_argument("tag")
    args = parser.parse_args()
    try:
        print(release_version(args.tag))
    except ValueError as error:
        parser.error(str(error))
