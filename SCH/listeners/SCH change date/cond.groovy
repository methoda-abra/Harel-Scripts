['Task','Change', 'Request', 'Sub Change'].includes(issue.issueType.name)
&& new Date().getTime() - issue.created.getTime() > 60000
