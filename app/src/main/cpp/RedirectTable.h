#pragma once
#include <string>
#include <unordered_map>
#include <mutex>

class RedirectTable {
public:
    static RedirectTable& GetInstance();

    // Adds a rule: e.g., "/data/data/com.viber.voip" -> "/data/user/0/.../virtual/com.viber.voip"
    void AddRule(const std::string& originalPrefix, const std::string& virtualPrefix);

    // Checks if a path matches a rule. Returns the new path, or an empty string if no rule matched.
    std::string ResolvePath(const char* originalPath);

private:
    RedirectTable() = default;
    ~RedirectTable() = default;

    // C# Naming convention
    std::unordered_map<std::string, std::string> _Rules;
    std::mutex _Lock;
};