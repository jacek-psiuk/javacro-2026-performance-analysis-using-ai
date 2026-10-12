package hr.javacro.section3;

/// One problem from section 1 or section 2, running alongside all the others in the [Workshop] JVM.
interface Scenario {

    /// Short id used to select scenarios on the command line, e.g. `s1ex4`.
    String id();

    /// What is wrong and which tool shows it.
    String description();

    /// Starts the scenario's threads and returns.
    void start();

    /// One short fragment for the periodic status line.
    String status();
}
