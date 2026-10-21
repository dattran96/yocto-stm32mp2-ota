cmake_minimum_required(VERSION 3.10)
project(sysmon VERSION 1.0 LANGUAGES C)

include(GNUInstallDirs)

add_executable(sysmon main.c)
target_compile_options(sysmon PRIVATE -Wall -Wextra)

option(WITH_SYSTEMD "Notify systemd (readiness, watchdog)" OFF)
if(WITH_SYSTEMD)
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(SYSTEMD REQUIRED libsystemd)
    target_compile_definitions(sysmon PRIVATE WITH_SYSTEMD)
    target_link_libraries(sysmon PRIVATE ${SYSTEMD_LIBRARIES})
endif()

install(TARGETS sysmon RUNTIME DESTINATION ${CMAKE_INSTALL_SBINDIR})
