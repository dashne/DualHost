#include "RedirectTable.h"
#include <cstring>

RedirectTable& RedirectTable::GetInstance() {
    static RedirectTable _Instance;
    return _Instance;
}

void RedirectTable::AddRule(const std::string& originalPrefix, const std::string& virtualPrefix) {
    std::lock_guard<std::mutex> guard(_Lock);
    _Rules[originalPrefix] = virtualPrefix;
}

std::string RedirectTable::ResolvePath(const char* originalPath) {
    if (!originalPath) return "";

    std::string path(originalPath);
    std::lock_guard<std::mutex> guard(_Lock);

    for (const auto& pair : _Rules) {
        if (path.rfind(pair.first, 0) == 0) { // Path starts with the prefix
            // Replace the prefix
            return pair.second + path.substr(pair.first.length());
        }
    }
    return ""; // No redirection needed
}